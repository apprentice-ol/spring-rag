package com.nageoffer.ai.rag.chat.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.rag.chat.util.LlmCallGuard;
import com.nageoffer.ai.rag.chat.util.TextPreviews;
import com.pgvector.PGvector;
import java.io.IOException;
import java.time.Duration;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.jjx.ai.llmobservability.observation.annotation.TelemetryStep;
import org.springframework.transaction.annotation.Transactional;

/**
 * 向量检索通道（直接 JDBC pgvector 版）。
 *
 * <p>手动 embed query → 拼 pgvector {@code <=>} cosine 距离 SQL → 返回 RetrievedChunk。
 * 不再使用 Spring AI {@code VectorStore} 抽象，从而独立控制 content 与 embedding 两列。 </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorSearchChannel implements SearchChannel {

    private static final Duration EMBED_TIMEOUT = Duration.ofSeconds(15);
    private static final int QUERY_TIMEOUT_SECONDS = 8;

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper;

    @Override
    public String getName() {
        return "vector";
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.VECTOR;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    @TelemetryStep("rag.channel")
    public SearchChannelResult search(SearchContext context) {
        long t0 = System.currentTimeMillis();

        String query = context.getRewrittenQuery() != null
                ? context.getRewrittenQuery() : context.getQuery();

        int topK = context.getBudget() != null
                ? context.getBudget().getRecallBudget() : context.getTopK();

        double threshold = context.getThreshold();

        // embed query (bounded: embedding HTTP hang must not leak pool threads forever)
        float[] queryVector;
        try {
            queryVector = LlmCallGuard.call(() -> embeddingModel.embed(query), EMBED_TIMEOUT, "vector embedding");
        } catch (Exception e) {
            log.warn("[VectorSearch] embedding failed, vector channel returns empty: {}", e.getMessage());
            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.VECTOR)
                    .channelName(getName())
                    .chunks(List.of())
                    .latencyMs(System.currentTimeMillis() - t0)
                    .build();
        }

        // 对齐 ragent PgVectorRetrieverService：ef_search=200 + iterative_scan=relaxed_order。
        // 默认 ef 偏小 + strict_order 会产生"召回悬崖"——漏掉语义稍远但内容命中的步骤块，
        // 剩下 cosine 分高但泛泛的概述块（"分两类"等高频词）。relaxed_order 让 HNSW 填满 LIMIT。
        //
        // GUC 是会话级：SET 与 SELECT 必须落在同一条连接上（分开 execute 可能各借不同池连接，
        // 既可能 GUC 未生效、又污染归还池后的随机会话），查询结束 finally 复位避免残留。
        // （SET LOCAL 对 iterative_scan 在部分 pgvector 版本不生效，故用 SET + RESET。）
        Long collectionId = context.getCollectionId();
        Set<String> restrictedDocIds = context.getRestrictedDocIds();

        StringBuilder sql = new StringBuilder("SELECT content, metadata, 1 - (embedding <=> ?::vector) AS similarity ")
                .append("FROM spring_ai_store_vector");
        List<Object> sqlParams = new ArrayList<>();
        sqlParams.add(new PGvector(queryVector));
        List<String> where = new ArrayList<>();
        if (collectionId != null) {
            where.add("metadata->>'collection_id' = ?");
            sqlParams.add(String.valueOf(collectionId));
        }
        if (restrictedDocIds != null && !restrictedDocIds.isEmpty()) {
            where.add("metadata->>'doc_id' IN (" + String.join(",", Collections.nCopies(restrictedDocIds.size(), "?")) + ")");
            sqlParams.addAll(restrictedDocIds);
        }
        if (!where.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", where));
        }
        sql.append(" ORDER BY embedding <=> ?::vector LIMIT ?");
        sqlParams.add(new PGvector(queryVector));
        sqlParams.add(topK);

        List<RetrievedChunk> candidates = jdbcTemplate.execute((ConnectionCallback<List<RetrievedChunk>>) conn -> {
            try (Statement st = conn.createStatement()) {
                st.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                st.execute("SET hnsw.ef_search = 200");
                st.execute("SET hnsw.iterative_scan = relaxed_order");
            }
            try {
                try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                    ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                    for (int i = 0; i < sqlParams.size(); i++) {
                        ps.setObject(i + 1, sqlParams.get(i));
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        List<RetrievedChunk> rows = new ArrayList<>();
                        while (rs.next()) {
                            rows.add(mapRow(rs));
                        }
                        return rows;
                    }
                }
            } finally {
                // 归还连接池前复位，GUC 不泄漏给后续无关查询
                try (Statement st = conn.createStatement()) {
                    st.execute("RESET hnsw.ef_search");
                    st.execute("RESET hnsw.iterative_scan");
                } catch (SQLException e) {
                    log.debug("[向量检索] RESET hnsw GUC 失败（忽略）: {}", e.getMessage());
                }
            }
        });

        // 阈值过滤移到 Java 侧：取回结果已按相似度降序，过滤不破坏顺序
        List<RetrievedChunk> chunks = candidates.stream()
                .filter(c -> c.getOriginalScore() != null && c.getOriginalScore() >= threshold)
                .toList();

        long elapsed = System.currentTimeMillis() - t0;
        log.info("[向量检索] query=\"{}\", topK={}, 阈值={}, 候选={}条, 过阈后={}条, 耗时={}ms",
                query, topK, threshold, candidates.size(), chunks.size(), elapsed);
        if (!chunks.isEmpty()) {
            for (int i = 0; i < Math.min(chunks.size(), 5); i++) {
                RetrievedChunk c = chunks.get(i);
                log.info("[向量检索]   #{} 得分={} 预览=\"{}\"",
                        i + 1, c.getScore(), TextPreviews.truncate(c.getContent(), 80));
            }
            if (chunks.size() > 5) {
                log.info("[向量检索]   ... 还有 {} 条", chunks.size() - 5);
            }
        }

        return SearchChannelResult.builder()
                .channelType(SearchChannelType.VECTOR)
                .channelName(getName())
                .chunks(chunks)
                .latencyMs(elapsed)
                .build();
    }

    /** 行 → RetrievedChunk（metadata JSON 容错解析，失败按空元数据继续） */
    private RetrievedChunk mapRow(ResultSet rs) throws SQLException {
        String content = rs.getString("content");
        double score = rs.getDouble("similarity");
        String metaJson = rs.getString("metadata");
        Map<String, Object> meta;
        try {
            meta = objectMapper.readValue(metaJson, new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            log.warn("[向量检索] 解析 metadata JSON 失败: {}", e.getMessage());
            meta = Map.of();
        }
        RetrievedChunk c = new RetrievedChunk(content, score, meta, SearchChannelType.VECTOR);
        c.setOriginalScore(score);
        return c;
    }
}
