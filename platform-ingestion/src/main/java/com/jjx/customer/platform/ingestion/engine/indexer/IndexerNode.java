package com.jjx.customer.platform.ingestion.engine.indexer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.customer.platform.common.exception.ClientException;
import com.jjx.customer.platform.config.properties.IngestionProperties;
import com.jjx.customer.platform.ingestion.engine.chunk.VectorChunk;
import com.jjx.customer.platform.ingestion.engine.chunk.strategy.BoundaryAwareSplitter;
import com.jjx.customer.platform.ingestion.engine.IngestionContext;
import com.jjx.customer.platform.ingestion.engine.enums.IngestionNodeType;
import com.jjx.customer.platform.ingestion.engine.IngestionNode;
import com.jjx.customer.platform.ingestion.engine.NodeConfig;
import com.jjx.customer.platform.ingestion.engine.NodeResult;
import com.jjx.customer.platform.ingestion.engine.parser.model.AssetRef;
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
    private final BoundaryAwareSplitter splitter;
    private final IngestionProperties ingestionProperties;

    public IndexerNode(ObjectMapper objectMapper, JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel,
                       BoundaryAwareSplitter splitter, IngestionProperties ingestionProperties) {
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingModel = embeddingModel;
        this.splitter = splitter;
        this.ingestionProperties = ingestionProperties;
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

        // 小块检索展开：父块切子块进向量表，父块原文存 sa_chunk_parent（生成侧由聚合器取回）
        List<VectorChunk> indexChunks = chunks;
        if (ingestionProperties.getChildChunk().isEnabled()) {
            Expanded expand = expandToChildren(chunks, metas);
            if (expand.parentsWritten() > 0) {
                indexChunks = expand.children();
                metas = expand.childMetas();
                embedSources = expand.childEmbedSources();
                log.info("[Indexer] 子块展开: {} 父块 → {} 子块（父块存档 {} 条）",
                        chunks.size(), indexChunks.size(), expand.parentsWritten());
            }
        }

        // 批量 embedding（一次 API 调用；失败回退逐块，保持单块失败跳过语义）
        float[][] vectors = embedBatch(embedSources);

        // 批量 INSERT（multi-row VALUES，每批 INSERT_BATCH_SIZE 条）
        int inserted = batchInsert(indexChunks, metas, vectors);

        // 只要有块写入成功就返回成功（已写入的 chunk 可被检索）；全部失败才算入库失败
        if (inserted == 0) {
            return NodeResult.fail(new ClientException("向量写入全部失败，成功 0 / " + chunks.size() + " 块"));
        }
        int failed = indexChunks.size() - inserted;
        log.info("[Indexer] 写入完成: 成功 {} 块, 跳过 {} 块（共 {} 块）", inserted, failed, indexChunks.size());
        return NodeResult.ok("已写入 " + inserted + " 个分块" + (failed > 0 ? "（" + failed + " 块失败已跳过）" : ""));
    }

    /** 子块展开结果。 */
    private record Expanded(List<VectorChunk> children, List<Map<String, Object>> childMetas,
                            List<String> childEmbedSources, int parentsWritten) {
    }

    /**
     * 小块检索、大块生成（2026-09-18）：把父块切成子块进向量表，父块原文写 {@code sa_chunk_parent}。
     *
     * <p>切分口径：对父块 {@code content} 用 {@link BoundaryAwareSplitter}（句子/段落边界优先，
     * 字符预算取 {@code rag.ingestion.child-chunk.*}）。<b>单块切不出多片时该块不展开</b>——
     * 小父块本身就是合格子块，强行挂 parent_key 只会让父块表与向量表双写同样内容。</p>
     *
     * <p>子块 metadata = 父块 metadata 全量 + {@code parent_key} + {@code child_index}
     * （检索命中子块后，聚合器按 parent_key 取回父块原文组装上下文；doc_id/outline_path 等
     * 父块字段继承，引用与评测口径不变）。子块 embed 源 = 子块 content 片段本身——语义集中
     * 正是子块化的目的（表格类 embeddingText 的 key-value 优化不适用于片段）。</p>
     *
     * @param parents 父块（chunker 产物，与 metas 一一对应；空位为 null）
     * @param metas   父块 metadata（与 parents 对齐）
     * @return 展开结果；无任何块可展开时 children == parents（原样返回）
     */
    private Expanded expandToChildren(List<VectorChunk> parents, List<Map<String, Object>> metas) {
        IngestionProperties.ChildChunk cfg = ingestionProperties.getChildChunk();
        List<VectorChunk> children = new ArrayList<>();
        List<Map<String, Object>> childMetas = new ArrayList<>();
        List<String> childEmbedSources = new ArrayList<>();
        List<Object[]> parentRows = new ArrayList<>();

        for (int i = 0; i < parents.size(); i++) {
            VectorChunk parent = parents.get(i);
            Map<String, Object> parentMeta = metas.get(i);
            if (parent == null || parentMeta == null || !StringUtils.hasText(parent.getContent())) {
                continue;
            }
            List<String> pieces = splitter.split(parent.getContent(),
                    cfg.getMinChars(), cfg.getTargetChars(), cfg.getMaxChars(), cfg.getOverlapChars());
            if (pieces.size() <= 1) {
                continue; // 小父块不展开（它自己就是子块）
            }
            String parentKey = parentKeyOf(parentMeta);
            for (int c = 0; c < pieces.size(); c++) {
                Map<String, Object> childMeta = new HashMap<>(parentMeta);
                childMeta.put("parent_key", parentKey);
                childMeta.put("child_index", c);
                children.add(VectorChunk.builder()
                        .chunkId(parent.getChunkId() == null ? null : parent.getChunkId() + "-" + c)
                        .index(parent.getIndex())
                        .content(pieces.get(c))
                        .blockType(parent.getBlockType())
                        .outlinePath(parent.getOutlinePath())
                        .sectionContext(parent.getSectionContext())
                        .assets(parent.getAssets())
                        .build());
                childMetas.add(childMeta);
                childEmbedSources.add(pieces.get(c));
            }
            parentRows.add(new Object[]{parentKey,
                    String.valueOf(parentMeta.get("doc_id")),
                    parentMeta.get("collection_id") == null ? null : parentMeta.get("collection_id"),
                    String.valueOf(parentMeta.getOrDefault("doc_name", "")),
                    String.valueOf(parentMeta.getOrDefault("outline_path", "")),
                    parent.getContent()});
        }

        int written = insertParents(parentRows);
        if (children.isEmpty()) {
            return new Expanded(parents, metas, childEmbedSources(parents), written);
        }
        return new Expanded(children, childMetas, childEmbedSources, written);
    }

    /**
     * 父块批量存档（{@code sa_chunk_parent}，ON CONFLICT 幂等）。
     *
     * @return 成功写入行数
     */
    private int insertParents(List<Object[]> rows) {
        int written = 0;
        for (int from = 0; from < rows.size(); from += INSERT_BATCH_SIZE) {
            int to = Math.min(from + INSERT_BATCH_SIZE, rows.size());
            StringBuilder values = new StringBuilder();
            List<Object> params = new ArrayList<>();
            for (int i = from; i < to; i++) {
                if (values.length() > 0) {
                    values.append(",");
                }
                values.append("(?, ?, ?::bigint, ?, ?, ?)");
                for (Object p : rows.get(i)) {
                    params.add(p);
                }
            }
            try {
                written += jdbcTemplate.update(
                        "INSERT INTO sa_chunk_parent (parent_key, doc_id, collection_id, doc_name, outline_path, content) "
                                + "VALUES " + values + " ON CONFLICT (parent_key) DO NOTHING",
                        params.toArray());
            } catch (Exception e) {
                log.error("[Indexer] 父块存档失败（跳过本批 {} 条）: {}", to - from, e.getMessage());
            }
        }
        return written;
    }

    /** 父块键：doc_id + 父块序号（确定性；同一 docId 重灌先删后插，键稳定可复现）。 */
    private static String parentKeyOf(Map<String, Object> parentMeta) {
        return String.valueOf(parentMeta.get("doc_id")) + ":" + parentMeta.get("chunk_index");
    }

    /** 展开未发生时，embed 源沿用父块口径（embeddingText 优先）。 */
    private static List<String> childEmbedSources(List<VectorChunk> parents) {
        List<String> sources = new ArrayList<>(parents.size());
        for (VectorChunk parent : parents) {
            sources.add(parent == null ? null
                    : (StringUtils.hasText(parent.getEmbeddingText())
                            ? parent.getEmbeddingText() : parent.getContent()));
        }
        return sources;
    }

    /**
     * 构建块的 metadata（doc_id/chunk_index/block_type/outline_path/section_context/assets + 白名单字段）。
     */
    private Map<String, Object> buildMeta(VectorChunk chunk, IngestionContext context,
                                          Map<String, Object> contextMeta, IndexerSettings settings) {
        Map<String, Object> meta = new HashMap<>();
        // 文档身份用 context.docId（调用方可指定，如 LiveRAG 导入器传源 urn），缺失才回退 taskId。
        // 早先直接写 taskId：那时调用方都不传 docId、effectiveDocId 恰好等于 taskId，所以看不出问题；
        // 一旦调用方指定了 docId（文档身份与任务身份分离），写 taskId 就会让 metadata.doc_id
        // 与评测的 expected_doc_ids 对不上——检索命中了也判不中，整轮评测全 0。
        meta.put("doc_id", StringUtils.hasText(context.getDocId())
                ? context.getDocId() : context.getTaskId());
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
