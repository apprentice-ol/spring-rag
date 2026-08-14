package com.nageoffer.ai.rag.chat.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.nageoffer.ai.obs.TraceStep;

/**
 * 关键词检索通道（pg_bm25 扩展版，Tantivy 内核真 BM25）。
 *
 * <p>与 {@link KeywordSearchChannel}（滑窗字词 SQL 版）二选一，由配置
 * {@code rag.search.channels.keyword.mode: sql | bm25} 指定（默认 sql，零依赖）。</p>
 *
 * <p><b>启用前置条件</b>（需先手工执行，本通道启动时校验、缺失则降级为空结果）：</p>
 * <pre>
 * -- 1) 安装扩展（ParadeDB 的 pg_bm25 已并入 pg_search，0.25.x 即真 BM25 + ngram 中文支持）
 * --    官方 apt 源已下线，从 GitHub releases 下载 deb：
 * --    postgresql-16-pg-search_0.25.0-1PARADEDB-bookworm_amd64.deb（PG16/Debian12）
 * --    且需 shared_preload_libraries = 'pg_search'（postgresql.conf）后重启 PG
 * CREATE EXTENSION pg_search;
 *
 * -- 2) 建 BM25 索引。注意：key_field 必须同时是索引列（USING bm25 (id, content)），
 * --    否则任何 INSERT 都会报 "No key field defined"、文档无法入库；
 * --    0.25 版已移除 WITH tokenizer 参数，默认 tokenizer 为 ngram，中文可直接检索
 * CREATE INDEX rag_keyword_bm25 ON spring_ai_store_vector
 *     USING bm25 (id, content) WITH (key_field = 'id');
 * </pre>
 *
 * <p><b>0.25 版 API 要点</b>（与旧版 pg_bm25 不同）：打分函数为 {@code pdb.score(列)}，
 * 查询运算符为 {@code content @@@ '词1 词2'}（空格分隔 = OR 语义；无空格的连续串按短语匹配，
 * 中文长句必须由拆词结果空格 join）。</p>
 *
 * <p><b>与 SQL 版的差异</b>：</p>
 * <ul>
 *   <li>打分：BM25（TF-IDF + 文档长度归一），而非滑窗命中计数——长文档惩罚、稀有词加权</li>
 *   <li>doc_name 文档路由（SQL 版条件 C）未纳入：bm25 索引仅建在 content 列，
 *       如需文档路由可在建索引时追加 doc_name 表达式列并扩展查询</li>
 *   <li>查询词构造：拆词（整词 + 2 字滑窗）后以空格 join（与 SQL 版"组内任一命中"一致，
 *       且规避无空格连续串被当短语匹配的问题）</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rag.search.channels.keyword", name = "enabled", havingValue = "true", matchIfMissing = false)
@ConditionalOnProperty(prefix = "rag.search.channels.keyword", name = "mode", havingValue = "bm25")
public class Bm25KeywordSearchChannel implements SearchChannel {

    private static final String TABLE_NAME = "spring_ai_store_vector";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Value("${rag.search.channels.keyword.bm25.index-name:rag_keyword_bm25}")
    private String indexName;

    /** 扩展/索引就绪标志；启动校验失败时置 false，检索降级为空（不阻断其他通道） */
    private volatile boolean available = false;

    public Bm25KeywordSearchChannel(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void verifyPrerequisites() {
        try {
            // pg_bm25 已并入 pg_search（0.25.x）；兼容两者都存在的情况
            Integer extCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_extension WHERE extname IN ('pg_search', 'pg_bm25')", Integer.class);
            Integer idxCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_indexes WHERE tablename = ? AND indexname = ?",
                    Integer.class, TABLE_NAME, indexName);
            if (extCount == null || extCount == 0) {
                log.error("[关键词检索][bm25] pg_search/pg_bm25 扩展未安装，降级为空结果。"
                        + "请安装 pg_search 扩展（0.25.x，GitHub releases deb）并 CREATE EXTENSION pg_search;"
                        + "（当前 mode=bm25）");
            } else if (idxCount == null || idxCount == 0) {
                // 索引缺失时自动创建（幂等）：Docker 部署零手工——
                // spring_ai_store_vector 表由应用启动后创建，索引只能在此刻建
                try {
                    jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS " + indexName + " ON " + TABLE_NAME
                            + " USING bm25 (id, content) WITH (key_field='id')");
                    available = true;
                    log.info("[关键词检索][bm25] 自动创建 BM25 索引 {} 成功", indexName);
                } catch (Exception ex) {
                    log.error("[关键词检索][bm25] BM25 索引 {} 自动创建失败，降级为空结果: {}。"
                            + "可手工执行 CREATE INDEX {} ON {} USING bm25 (id, content) WITH (key_field='id');"
                            + "（key_field 必须是索引列，否则 INSERT 报 No key field defined）",
                            indexName, ex.getMessage(), indexName, TABLE_NAME);
                }
            } else {
                available = true;
                log.info("[关键词检索][bm25] pg_search 就绪，索引={}", indexName);
            }
        } catch (Exception e) {
            log.error("[关键词检索][bm25] 就绪校验异常，降级为空结果", e);
        }
    }

    @Override
    public String getName() {
        return "keyword";
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.KEYWORD;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return true;
    }

    @Override
    @TraceStep("rag.channel")
    public SearchChannelResult search(SearchContext context) {
        long t0 = System.currentTimeMillis();
        if (!available) {
            log.warn("[关键词检索][bm25] 未就绪（扩展/索引缺失），返回空结果");
            return emptyResult(t0);
        }

        // 与 SQL 版一致用改写后 query（LLM 改写保留全部语义与专有名词）
        String query = context.getRewrittenQuery() != null
                ? context.getRewrittenQuery() : context.getQuery();
        if (query == null) {
            query = "";
        }

        int topK = context.getBudget() != null
                ? context.getBudget().getRecallBudget() : context.getTopK();

        // 拆词（整词 + 2 字滑窗，与 SQL 版共用）：空格 join 后走 Tantivy OR 语义
        QueryTermSplitter.SplitResult split = QueryTermSplitter.split(query);
        String[] terms = split.terms();
        if (terms.length == 0) {
            log.warn("[关键词检索][bm25] query=\"{}\" 拆分后无有效关键词", query);
            return emptyResult(t0);
        }
        String queryString = String.join(" ", terms);

        List<RetrievedChunk> chunks = jdbcTemplate.query(
                "SELECT content, metadata, pdb.score(content) AS bm25_score FROM " + TABLE_NAME
                        + " WHERE content @@@ ? ORDER BY pdb.score(content) DESC LIMIT ?",
                new Object[]{queryString, topK},
                (rs, rowNum) -> {
                    String content = rs.getString("content");
                    double score = rs.getDouble("bm25_score");
                    String metaJson = rs.getString("metadata");
                    Map<String, Object> meta;
                    try {
                        meta = objectMapper.readValue(metaJson, new TypeReference<Map<String, Object>>() {});
                    } catch (IOException e) {
                        log.warn("[关键词检索][bm25] 解析 metadata JSON 失败: {}", e.getMessage());
                        meta = Map.of();
                    }
                    RetrievedChunk c = new RetrievedChunk(content, score, meta, SearchChannelType.KEYWORD);
                    c.setOriginalScore(score);
                    return c;
                });

        long elapsed = System.currentTimeMillis() - t0;
        log.info("[关键词检索][bm25] query=\"{}\", topK={}, 拆分词={}, 命中={}条, 耗时={}ms",
                query, topK, String.join(",", terms), chunks.size(), elapsed);
        if (!chunks.isEmpty()) {
            for (int i = 0; i < Math.min(chunks.size(), 3); i++) {
                RetrievedChunk c = chunks.get(i);
                log.info("[关键词检索][bm25]   #{} BM25分={} 预览=\"{}\"",
                        i + 1, c.getScore() != null ? String.format("%.2f", c.getScore()) : "N/A",
                        truncate(c.getContent(), 80));
            }
            if (chunks.size() > 3) {
                log.info("[关键词检索][bm25]   ... 还有 {} 条", chunks.size() - 3);
            }
        }

        return SearchChannelResult.builder()
                .channelType(SearchChannelType.KEYWORD)
                .channelName(getName())
                .chunks(chunks)
                .latencyMs(elapsed)
                .build();
    }

    private SearchChannelResult emptyResult(long startTime) {
        return SearchChannelResult.builder()
                .channelType(SearchChannelType.KEYWORD)
                .channelName(getName())
                .chunks(List.of())
                .latencyMs(System.currentTimeMillis() - startTime)
                .build();
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
