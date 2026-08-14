package com.nageoffer.ai.rag.chat.retrieval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.nageoffer.ai.obs.TraceStep;

/**
 * 关键词检索通道（metadata.keywords + 文档名路由）。
 *
 * <p>基于 Enricher 产出的 {@code metadata.keywords} 与文档名做精确词召回，与向量通道互补：
 * 擅长专有名词、编号、操作名等向量弱信号词（对齐 ragent 的 ES BM25 通道，无 ES 时的本地实现）。</p>
 *
 * <p>匹配策略（query 拆词后，每个词命中任一条件即计 1 次）：</p>
 * <ul>
 *   <li><b>keywords 包含词</b>：chunk 关键词含查询词（"冲红" ⊂ "专用发票冲红"）</li>
 *   <li><b>词包含 keywords</b>：查询词含 chunk 关键词（≥2 字，"红字发票信息表怎么上传" ⊃ "红字发票"/"信息表"/"上传"），
 *       覆盖无分词场景下长查询拆不出的情况</li>
 *   <li><b>文档名路由</b>：{@code doc_name} 含查询词（"增值税发票冲红流程" ⊂ "手把手教你：增值税发票冲红流程.pdf"），
 *       等价于 ragent 的「意图→库作用域」收敛：整篇目标文档的 chunk 直接进候选</li>
 * </ul>
 *
 * <p>匹配后按命中词数排序、得分 clamp 到 0~1；同分按 chunk_index（阅读顺序）。</p>
 * <p>性能说明：JSONB 数组 EXISTS 无索引，全表扫描；当前库 327 块毫秒级可接受，
 * 量级上来后需引入分词（如 IK）与倒排索引。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rag.search.channels.keyword", name = "enabled", havingValue = "true", matchIfMissing = false)
@ConditionalOnProperty(prefix = "rag.search.channels.keyword", name = "mode", havingValue = "sql", matchIfMissing = true)
public class KeywordSearchChannel implements SearchChannel {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public KeywordSearchChannel(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
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

        // 与向量通道一致用改写后 query（LLM 改写保证保留全部语义与专有名词，同时去除"请问"等噪声）
        String query = context.getRewrittenQuery() != null
                ? context.getRewrittenQuery() : context.getQuery();
        if (query == null) {
            query = "";
        }

        int topK = context.getBudget() != null
                ? context.getBudget().getRecallBudget() : context.getTopK();

        // 把 query 拆成关键词（整词 + 2 字滑窗子词，与 bm25 模式共用 QueryTermSplitter）
        QueryTermSplitter.SplitResult split = QueryTermSplitter.split(query);
        String[] terms = split.terms();
        Set<String> wholeTerms = split.wholeTerms();

        // 无有效关键词时返回空
        if (terms.length == 0) {
            log.warn("[关键词检索] query=\"{}\" 拆分后无有效关键词", query);
            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.KEYWORD)
                    .channelName(getName())
                    .chunks(List.of())
                    .latencyMs(System.currentTimeMillis() - t0)
                    .build();
        }

        // 每个 term 一组条件（组内任一命中计 1 次）：
        //  A) keywords 元素包含 term          —— 精确词落在 chunk 关键词里
        //  B) term 包含 ≥2 字的 keywords 元素  —— 长查询含 chunk 关键词（无分词兜底）
        //  C) doc_name 包含 term              —— 文档名路由：整篇目标文档进候选
        // 条件 C 仅对整词生效：滑窗子词是块级区分信号，若也带 doc_name 路由，
        // 每个子词都会因文档名命中而计分，全部块同分、排序退化回 chunk_index。
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT content, metadata, (");
        List<Object> params = new ArrayList<>();
        List<String> selectGroups = new ArrayList<>();
        List<String> whereGroups = new ArrayList<>();
        for (String t : terms) {
            String kwCond = "(EXISTS (SELECT 1 FROM jsonb_array_elements_text(metadata::jsonb -> 'keywords') kw"
                    + " WHERE kw ILIKE '%' || ? || '%')"
                    + " OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(metadata::jsonb -> 'keywords') kw"
                    + " WHERE char_length(kw) >= 2 AND ? ILIKE '%' || kw || '%'))";
            String docCond = "(metadata::jsonb ->> 'doc_name' ILIKE '%' || ? || '%')";
            String cond = wholeTerms.contains(t) ? kwCond + " OR " + docCond : kwCond;
            // SELECT 侧计数需 ::int（整词 cond 是 kwCond OR docCond 并列括号，须整体再包一层，
            // 否则 ::int 只作用于末位括号，变成 boolean OR integer）；WHERE 侧必须是布尔表达式
            selectGroups.add("(" + cond + ")::int");
            whereGroups.add(cond);
            // cond 在 SELECT 与 WHERE 各出现一次（每处 2~3 个占位符），共需绑定 4~6 个参数
            params.add(t);
            params.add(t);
            if (wholeTerms.contains(t)) {
                params.add(t);
            }
            params.add(t);
            params.add(t);
            if (wholeTerms.contains(t)) {
                params.add(t);
            }
        }
        sql.append(String.join(" + ", selectGroups));
        sql.append(") AS hit_cnt FROM spring_ai_store_vector WHERE ");
        sql.append(String.join(" OR ", whereGroups));
        // 命中数降序；同分按文档内阅读顺序（doc 路由会把整篇文档拉进来，chunk_index 还原原文顺序）
        sql.append(" ORDER BY hit_cnt DESC, (metadata::jsonb ->> 'chunk_index')::int ASC LIMIT ?");
        params.add(topK);

        List<RetrievedChunk> chunks = jdbcTemplate.query(
                sql.toString(),
                params.toArray(),
                (rs, rowNum) -> {
                    String content = rs.getString("content");
                    int hitCnt = rs.getInt("hit_cnt");
                    String metaJson = rs.getString("metadata");
                    Map<String, Object> meta;
                    try {
                        meta = objectMapper.readValue(metaJson, new TypeReference<Map<String, Object>>() {});
                    } catch (IOException e) {
                        log.warn("[关键词检索] 解析 metadata JSON 失败: {}", e.getMessage());
                        meta = Map.of();
                    }
                    // 命中比例 0~1：命中词数 / 拆词数，clamp 防长查询多条命中超 1.0
                    double hitRatio = Math.min(1.0, (double) hitCnt / terms.length);
                    RetrievedChunk c = new RetrievedChunk(content, hitRatio, meta, SearchChannelType.KEYWORD);
                    c.setOriginalScore(hitRatio);
                    return c;
                });

        long elapsed = System.currentTimeMillis() - t0;
        log.info("[关键词检索] query=\"{}\", topK={}, 拆分词={}, 命中={}条, 耗时={}ms",
                query, topK, String.join(",", terms), chunks.size(), elapsed);
        if (!chunks.isEmpty()) {
            for (int i = 0; i < Math.min(chunks.size(), 3); i++) {
                RetrievedChunk c = chunks.get(i);
                log.info("[关键词检索]   #{} 命中比={} 预览=\"{}\"",
                        i + 1, c.getScore() != null ? String.format("%.2f", c.getScore()) : "N/A",
                        truncate(c.getContent(), 80));
            }
            if (chunks.size() > 3) {
                log.info("[关键词检索]   ... 还有 {} 条", chunks.size() - 3);
            }
        }

        return SearchChannelResult.builder()
                .channelType(SearchChannelType.KEYWORD)
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
