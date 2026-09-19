package com.jjx.customer.platform.ingestion.migration;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.customer.platform.config.properties.IngestionProperties;
import com.jjx.customer.platform.ingestion.engine.IngestionContext;
import com.jjx.customer.platform.ingestion.engine.NodeConfig;
import com.jjx.customer.platform.ingestion.engine.chunk.VectorChunk;
import com.jjx.customer.platform.ingestion.engine.chunk.strategy.RecursiveBoundarySplitter;
import com.jjx.customer.platform.ingestion.engine.fetcher.DocumentSource;
import com.jjx.customer.platform.ingestion.engine.indexer.IndexerNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 一次性回填工具（<b>非常规测试</b>）：把存量父块行展开成子块 + 父块存档，
 * 复用生产代码 {@link IndexerNode}（真实切分器与父块存档逻辑，零口径分叉）。
 *
 * <p><b>为什么需要它</b>：正常重灌要走完整管道（解析→分块→富集→索引），而富集是每块一次
 * LLM 调用（5122 块 ≈ 数十分钟 + 成本）；存量行已经带着富集产物（keywords 等 metadata），
 * 直接就地展开可跳过解析与富集，只花子块 embedding 的钱。</p>
 *
 * <p><b>用法</b>（默认跳过，必须显式开开关）：</p>
 * <pre>
 *   BAILIAN_API_KEY=xxx mvn -pl platform-ingestion test -Dtest=ParentChildBackfillTool \
 *       -Dmigration.parentChild=true [-Dmigration.docIds=id1,id2] [-Dmigration.dryRun=true]
 * </pre>
 *
 * <p>连接参数可用 {@code -Dmigration.jdbcUrl=} / {@code -Dmigration.user=} / {@code -Dmigration.password=} 覆盖。</p>
 *
 * <p><b>幂等性</b>：只处理 {@code parent_key IS NULL} 的行（已展开的文档自动跳过）；
 * 展开成功后才删旧行，中途失败不会留下半截状态（旧行仍在，重跑覆盖）。</p>
 */
class ParentChildBackfillTool {

    private static final String DEFAULT_URL = "jdbc:postgresql://localhost:5432/springai_rag";

    @Test
    void backfill() throws Exception {
        if (!Boolean.getBoolean("migration.parentChild")) {
            return; // 默认跳过：这不是常规测试
        }
        boolean dryRun = Boolean.getBoolean("migration.dryRun");
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                System.getProperty("migration.jdbcUrl", DEFAULT_URL),
                System.getProperty("migration.user", "postgres"),
                System.getProperty("migration.password", "postgres")));

        String filter = System.getProperty("migration.docIds", "");
        List<String> docIds = jdbc.queryForList("""
                SELECT DISTINCT metadata->>'doc_id' FROM spring_ai_store_vector
                WHERE metadata->>'parent_key' IS NULL
                  AND metadata->>'doc_id' IS NOT NULL
                  AND (? = '' OR metadata->>'doc_id' = ANY(string_to_array(?, ',')))
                ORDER BY 1""", String.class, filter, filter);

        System.out.printf("[backfill] 待展开文档 %d 篇（dryRun=%s）%n", docIds.size(), dryRun);
        IngestionProperties props = new IngestionProperties(); // 用 yaml 同款默认值（400/500/120/60）
        ObjectMapper mapper = new ObjectMapper();
        IndexerNode indexer = new IndexerNode(mapper, jdbc,
                dryRun ? noopEmbedding() : dashscopeEmbedding(mapper), new RecursiveBoundarySplitter(), props);

        int done = 0;
        for (String docId : docIds) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT id::text AS id, content, metadata::text AS meta
                    FROM spring_ai_store_vector
                    WHERE metadata->>'doc_id' = ? AND metadata->>'parent_key' IS NULL
                    ORDER BY (metadata->>'chunk_index')::int NULLS FIRST""", docId);
            if (rows.isEmpty()) {
                continue;
            }
            List<VectorChunk> parents = new ArrayList<>(rows.size());
            Long collectionId = null;
            String docName = "";
            for (Map<String, Object> row : rows) {
                Map<String, Object> meta = mapper.readValue((String) row.get("meta"),
                        new TypeReference<Map<String, Object>>() { });
                if (collectionId == null && meta.get("collection_id") instanceof Number n) {
                    collectionId = n.longValue();
                }
                if (docName.isEmpty() && meta.get("doc_name") != null) {
                    docName = String.valueOf(meta.get("doc_name"));
                }
                parents.add(toVectorChunk(meta, (String) row.get("content")));
            }
            IngestionContext ctx = IngestionContext.builder()
                    .taskId(UUID.randomUUID().toString())
                    .docId(docId)
                    .chunks(parents)
                    .collectionId(collectionId)
                    .source(DocumentSource.builder().fileName(docName).build())
                    .build();
            var result = indexer.execute(ctx, NodeConfig.builder().nodeType("indexer").build());
            if (!result.isSuccess()) {
                System.out.printf("[backfill] !! 展开失败 docId=%s: %s%n", docId, result.getMessage());
                continue;
            }
            if (!dryRun) {
                // 只清「确实已存档」的父块行：切不出多片的短父块没被展开（expandToChildren 的
                // continue 分支），它本身就是合格子块，删掉等于丢内容——留在库里走聚合器的直通分支。
                // 判据 = sa_chunk_parent 里存在 docId:chunkIndex 这条存档。
                int removed = jdbc.update("""
                        DELETE FROM spring_ai_store_vector v
                        WHERE v.metadata->>'doc_id' = ? AND v.metadata->>'parent_key' IS NULL
                          AND EXISTS (SELECT 1 FROM sa_chunk_parent p
                                      WHERE p.parent_key = (v.metadata->>'doc_id') || ':' || (v.metadata->>'chunk_index'))""",
                        docId);
                System.out.printf("[backfill] %s 父块 %d → 子块展开，旧行清理 %d%n", docId, rows.size(), removed);
            } else {
                System.out.printf("[backfill][dryRun] %s 父块 %d 篇（未写库）%n", docId, rows.size());
            }
            done++;
        }
        System.out.printf("[backfill] 完成：%d / %d 篇%n", done, docIds.size());
        assertTrue(done == docIds.size(), "有文档展开失败，见上方日志");
    }

    /** DB metadata → VectorChunk（把 buildMeta 需要的字段还原回去，keywords 走 metadata 白名单保留）。 */
    private static VectorChunk toVectorChunk(Map<String, Object> meta, String content) {
        Object index = meta.get("chunk_index");
        List<String> outline = meta.get("outline_path") == null ? List.of()
                : List.of(String.valueOf(meta.get("outline_path")));
        return VectorChunk.builder()
                .chunkId(UUID.randomUUID().toString())
                .index(index instanceof Number n ? n.intValue() : null)
                .content(content)
                .embeddingText(null)
                .blockType(meta.get("block_type") == null ? null : String.valueOf(meta.get("block_type")))
                .outlinePath(new ArrayList<>(outline))
                .sectionContext(meta.get("section_context") == null ? null
                        : String.valueOf(meta.get("section_context")))
                .metadata(new LinkedHashMap<>(Map.of("keywords",
                        meta.getOrDefault("keywords", List.of()))))
                .build();
    }

    /** dryRun 用：返回零向量（不调用外部 API）。 */
    private static EmbeddingModel noopEmbedding() {
        return new EmbeddingModel() {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                throw new UnsupportedOperationException("dryRun");
            }

            @Override
            public float[] embed(Document document) {
                return new float[1024];
            }

            @Override
            public List<float[]> embed(List<String> texts) {
                List<float[]> out = new ArrayList<>();
                for (int i = 0; i < texts.size(); i++) {
                    out.add(new float[1024]);
                }
                return out;
            }
        };
    }

    /** 百炼 text-embedding-v3（与生产同模型同维度；key 取环境变量 BAILIAN_API_KEY）。 */
    private static EmbeddingModel dashscopeEmbedding(ObjectMapper mapper) {
        String apiKey = System.getenv("BAILIAN_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("缺少环境变量 BAILIAN_API_KEY");
        }
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return new EmbeddingModel() {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                throw new UnsupportedOperationException("只实现 embed(List)");
            }

            @Override
            public float[] embed(Document document) {
                return embed(List.of(document.getText())).getFirst();
            }

            @Override
            public List<float[]> embed(List<String> texts) {
                try {
                    Map<String, Object> body = Map.of(
                            "model", "text-embedding-v3",
                            "input", texts,
                            "dimensions", 1024);
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings"))
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(60))
                            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                            .build();
                    HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() != 200) {
                        throw new IllegalStateException("embedding HTTP " + resp.statusCode() + ": " + resp.body());
                    }
                    JsonNode data = mapper.readTree(resp.body()).path("data");
                    List<float[]> vectors = new ArrayList<>();
                    for (JsonNode item : data) {
                        JsonNode vec = item.path("embedding");
                        float[] raw = new float[vec.size()];
                        for (int i = 0; i < vec.size(); i++) {
                            raw[i] = (float) vec.get(i).asDouble();
                        }
                        vectors.add(raw);
                    }
                    return vectors;
                } catch (Exception e) {
                    throw new IllegalStateException("embedding 调用失败: " + e.getMessage(), e);
                }
            }
        };
    }
}
