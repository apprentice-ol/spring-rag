package com.nageoffer.ai.rag.chat.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.nageoffer.ai.rag.config.telemetry.TraceStep;
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
    @TraceStep("rag.channel")
    public SearchChannelResult search(SearchContext context) {
        long t0 = System.currentTimeMillis();

        String query = context.getRewrittenQuery() != null
                ? context.getRewrittenQuery() : context.getQuery();

        int topK = context.getBudget() != null
                ? context.getBudget().getRecallBudget() : context.getTopK();

        double threshold = context.getThreshold();

        // embed query
        float[] queryVector = embeddingModel.embed(query);

        // 对齐 ragent PgVectorRetrieverService：ef_search=200 + iterative_scan=relaxed_order。
        // 默认 ef 偏小 + strict_order 会产生"召回悬崖"——漏掉语义稍远但内容命中的步骤块，
        // 剩下 cosine 分高但泛泛的概述块（"分两类"等高频词）。relaxed_order 让 HNSW 填满 LIMIT。
        // iterative_scan 用 SET（session 级；SET LOCAL 对该 GUC 在部分 pgvector 版本不生效），
        // 它对所有向量检索都有益，session 残留无副作用。
        jdbcTemplate.execute("SET hnsw.ef_search = 200");
        jdbcTemplate.execute("SET hnsw.iterative_scan = relaxed_order");

        // pgvector cosine 距离：<=> 返回 0~2，1 - <=> 转为相似度 0~1。
        // 关键：ORDER BY 用裸 <=> 让 HNSW 索引命中；阈值过滤移到 Java 侧——
        // 旧版 WHERE 1-(embedding<=>?)>=? 的表达式会使 HNSW 退化为近似全表扫描。
        String sql = "SELECT content, metadata, 1 - (embedding <=> ?::vector) AS similarity " +
                     "FROM spring_ai_store_vector " +
                     "ORDER BY embedding <=> ?::vector LIMIT ?";

        List<RetrievedChunk> candidates = jdbcTemplate.query(
                sql,
                new Object[]{new PGvector(queryVector), new PGvector(queryVector), topK},
                (rs, rowNum) -> {
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
                        i + 1, c.getScore(), truncate(c.getContent(), 80));
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

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
