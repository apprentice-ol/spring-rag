package com.nageoffer.ai.rag.ingestion.engine.indexer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.rag.common.exception.ClientException;
import com.nageoffer.ai.rag.ingestion.engine.chunk.VectorChunk;
import com.nageoffer.ai.rag.ingestion.engine.IngestionContext;
import com.nageoffer.ai.rag.ingestion.engine.enums.IngestionNodeType;
import com.nageoffer.ai.rag.ingestion.engine.IngestionNode;
import com.nageoffer.ai.rag.ingestion.engine.NodeConfig;
import com.nageoffer.ai.rag.ingestion.engine.NodeResult;
import com.nageoffer.ai.rag.ingestion.engine.parser.model.AssetRef;
import com.pgvector.PGvector;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 索引节点（直接 JDBC pgvector 版）。
 *
 * <p>不再使用 Spring AI {@code VectorStore} 抽象，直接 JDBC 写入 pgvector 表，
 * 从而独立控制 content（展示用原文）与 embedding（从 embeddingText 计算）两列。
 *
 * <p>VectorChunk → pgvector 行映射：
 * <ul>
 *   <li>content = chunk.getContent()（原始正文，供 LLM 展示/回填）</li>
 *   <li>embedding = EmbeddingModel.embed(embeddingText ?: content)</li>
 *   <li>metadata: doc_id/chunk_index/block_type/outline_path/section_context/assets + 白名单字段</li>
 * </ul>
 *
 * <p><b>批量化</b>（对齐 ragent 入库速度）：embedding 批量调用（一次 API 请求多块文本）、
 * INSERT 批量 multi-row（每批 50 条）——入库耗时的主要瓶颈从"每块一次 HTTP"变为单批一次。
 * 失败语义保持：批量 embedding 失败回退逐块；单块 embed/序列化失败跳过该块不中断整篇。</p>
 */
@Slf4j
@Component
public class IndexerNode implements IngestionNode {

    /** 批量 INSERT 每批行数（PG 参数上限 65535，50 行 × 4 参数 = 200，远低于上限） */
    private static final int INSERT_BATCH_SIZE = 50;

    /** embedding 单次批量上限（百炼 text-embedding-v3 限制 ≤10 条/次，超限返回 400） */
    private static final int EMBED_BATCH_SIZE = 10;

    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingModel embeddingModel;

    public IndexerNode(ObjectMapper objectMapper, JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingModel = embeddingModel;
    }

    @Override
    public String getNodeType() {
        return IngestionNodeType.INDEXER.getValue();
    }

    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        List<VectorChunk> chunks = context.getChunks();
        if (chunks == null || chunks.isEmpty()) {
            return NodeResult.fail(new ClientException("没有可索引的分块"));
        }
        IndexerSettings settings = parseSettings(config.getSettings());

        if (context.isSkipIndexerWrite()) {
            return NodeResult.ok("已准备 " + chunks.size() + " 个分块（写入由调用方完成）");
        }

        Map<String, Object> contextMeta = context.getMetadata() == null ? Map.of() : context.getMetadata();

        // 第一遍：构建每块的行数据（embedSource + metadata），顺序与 chunks 对齐
        List<String> embedSources = new ArrayList<>(chunks.size());
        List<Map<String, Object>> metas = new ArrayList<>(chunks.size());
        for (VectorChunk chunk : chunks) {
            if (chunk == null || !StringUtils.hasText(chunk.getContent())) {
                embedSources.add(null);
                metas.add(null);
                continue;
            }
            // 优先用 embeddingText（表格 key-value 等，语义更清晰），无则回退 content
            embedSources.add(StringUtils.hasText(chunk.getEmbeddingText())
                    ? chunk.getEmbeddingText() : chunk.getContent());
            metas.add(buildMeta(chunk, context, contextMeta, settings));
        }

        // 批量 embedding（一次 API 调用；失败回退逐块，保持单块失败跳过语义）
        float[][] vectors = embedBatch(embedSources);

        // 批量 INSERT（multi-row VALUES，每批 INSERT_BATCH_SIZE 条）
        int inserted = batchInsert(chunks, metas, vectors);

        // 只要有块写入成功就返回成功（已写入的 chunk 可被检索）；全部失败才算入库失败
        if (inserted == 0) {
            return NodeResult.fail(new ClientException("向量写入全部失败，成功 0 / " + chunks.size() + " 块"));
        }
        int failed = chunks.size() - inserted;
        log.info("[Indexer] 写入完成: 成功 {} 块, 跳过 {} 块（共 {} 块）", inserted, failed, chunks.size());
        return NodeResult.ok("已写入 " + inserted + " 个分块" + (failed > 0 ? "（" + failed + " 块失败已跳过）" : ""));
    }

    /**
     * 构建块的 metadata（doc_id/chunk_index/block_type/outline_path/section_context/assets + 白名单字段）。
     */
    private Map<String, Object> buildMeta(VectorChunk chunk, IngestionContext context,
                                          Map<String, Object> contextMeta, IndexerSettings settings) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("doc_id", context.getTaskId());
        // 集合归属：检索侧按 collection 过滤的依据（null=独立文件，不写该键）
        if (context.getCollectionId() != null) {
            meta.put("collection_id", context.getCollectionId());
        }
        if (context.getSource() != null && StringUtils.hasText(context.getSource().getFileName())) {
            meta.put("doc_name", context.getSource().getFileName());
        }
        if (chunk.getIndex() != null) {
            meta.put("chunk_index", chunk.getIndex());
        }
        if (StringUtils.hasText(chunk.getBlockType())) {
            meta.put("block_type", chunk.getBlockType());
        }
        if (chunk.getOutlinePath() != null && !chunk.getOutlinePath().isEmpty()) {
            meta.put("outline_path", String.join(" > ", chunk.getOutlinePath()));
        }
        if (StringUtils.hasText(chunk.getSectionContext())) {
            meta.put("section_context", chunk.getSectionContext());
        }
        if (chunk.getAssets() != null && !chunk.getAssets().isEmpty()) {
            meta.put("assets", chunk.getAssets().stream().map(AssetRef::publicUrl).toList());
        }
        // 白名单字段（keywords 等增强字段）；无 DB 配置时默认 keywords
        // （服务器新部署的 sa_ingestion_pipeline_node 为空，缺此默认则 keywords 不落库，
        //  票种过滤/关键词检索依赖 metadata.keywords）
        if (settings.getMetadataFields() == null || settings.getMetadataFields().isEmpty()) {
            settings.setMetadataFields(List.of("keywords"));
        }
        if (settings.getMetadataFields() != null) {
            Map<String, Object> combined = new HashMap<>(contextMeta);
            if (chunk.getMetadata() != null) {
                combined.putAll(chunk.getMetadata());
            }
            for (String field : settings.getMetadataFields()) {
                if (!StringUtils.hasText(field)) {
                    continue;
                }
                Object value = combined.get(field);
                if (value != null) {
                    meta.put(field, value);
                }
            }
        }
        return meta;
    }

    /**
     * 批量 embedding：一次 API 调用全部文本；整批失败回退逐块（保持单块失败跳过的语义）。
     *
     * @return 与入参对齐的向量数组；某块 embedding 失败时对应位为 null（该块跳过）
     */
    private float[][] embedBatch(List<String> sources) {
        float[][] vectors = new float[sources.size()][];
        List<Integer> validIdx = new ArrayList<>();
        List<String> validTexts = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            if (sources.get(i) != null) {
                validIdx.add(i);
                validTexts.add(sources.get(i));
            }
        }
        if (validTexts.isEmpty()) {
            return vectors;
        }
        // 按 EMBED_BATCH_SIZE 手动分批：百炼 text-embedding-v3 批量上限 10 条/次，
        // Spring AI 默认 BatchingStrategy 一次发全部会 400（batch size > 10）→ 回退逐块
        for (int from = 0; from < validTexts.size(); from += EMBED_BATCH_SIZE) {
            int to = Math.min(from + EMBED_BATCH_SIZE, validTexts.size());
            List<String> sub = validTexts.subList(from, to);
            try {
                List<float[]> batch = embeddingModel.embed(sub);
                for (int j = 0; j < batch.size(); j++) {
                    vectors[validIdx.get(from + j)] = batch.get(j);
                }
            } catch (Exception e) {
                // 该批失败回退逐块（保持单块失败跳过的语义）
                log.warn("[Indexer] 批量 embedding 失败（{}），本批回退逐块", e.getMessage());
                for (int j = from; j < to; j++) {
                    try {
                        vectors[validIdx.get(j)] = embeddingModel.embed(validTexts.get(j));
                    } catch (Exception e2) {
                        log.error("[Indexer] 单块 embedding 失败（跳过该块）: {}", e2.getMessage());
                    }
                }
            }
        }
        return vectors;
    }

    /**
     * 批量 INSERT（multi-row VALUES，每批 {@link #INSERT_BATCH_SIZE} 条）。
     * 单块序列化失败跳过该块；整批写入失败跳过本批（不中断后续批次）。
     *
     * @return 成功写入的块数
     */
    private int batchInsert(List<VectorChunk> chunks, List<Map<String, Object>> metas, float[][] vectors) {
        int inserted = 0;
        StringBuilder values = new StringBuilder();
        List<Object> params = new ArrayList<>();
        int rowCount = 0;

        for (int i = 0; i < chunks.size(); i++) {
            float[] vec = vectors[i];
            if (vec == null || metas.get(i) == null) {
                continue; // embedding/序列化失败，跳过该块
            }
            String metaJson;
            try {
                metaJson = objectMapper.writeValueAsString(metas.get(i));
            } catch (Exception e) {
                log.warn("[Indexer] 块 metadata 序列化失败（跳过该块）: chunk_index={}", chunks.get(i).getIndex());
                continue;
            }
            if (rowCount > 0) {
                values.append(",");
            }
            values.append("(?, ?, ?::jsonb, ?::vector)");
            params.add(UUID.randomUUID());
            params.add(chunks.get(i).getContent());
            params.add(metaJson);
            params.add(new PGvector(vec));
            rowCount++;

            if (rowCount >= INSERT_BATCH_SIZE) {
                inserted += doInsert(values.toString(), params);
                values = new StringBuilder();
                params = new ArrayList<>();
                rowCount = 0;
            }
        }
        if (rowCount > 0) {
            inserted += doInsert(values.toString(), params);
        }
        return inserted;
    }

    private int doInsert(String valuesSql, List<Object> params) {
        try {
            return jdbcTemplate.update(
                    "INSERT INTO spring_ai_store_vector (id, content, metadata, embedding) VALUES " + valuesSql,
                    params.toArray());
        } catch (Exception e) {
            // 单批失败跳过本批（与"单块失败跳过"同语义的批级版本），避免整篇因一批异常而失败
            log.error("[Indexer] 批量写入失败（跳过本批 {} 条）: {}", params.size() / 4, e.getMessage());
            return 0;
        }
    }

    private IndexerSettings parseSettings(JsonNode node) {
        if (node == null || node.isNull()) {
            return IndexerSettings.builder().build();
        }
        return objectMapper.convertValue(node, IndexerSettings.class);
    }
}
