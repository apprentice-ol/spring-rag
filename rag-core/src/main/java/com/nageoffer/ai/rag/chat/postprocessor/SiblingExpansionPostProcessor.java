package com.nageoffer.ai.rag.chat.postprocessor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.rag.chat.retrieval.RetrievedChunk;
import com.nageoffer.ai.rag.chat.retrieval.SearchChannelResult;
import com.nageoffer.ai.rag.chat.retrieval.SearchChannelType;
import com.nageoffer.ai.rag.chat.retrieval.SearchContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 同章节兄弟扩展后处理器（dedup 之前，最先执行）。
 * <p>
 * 解决"切分散导致章节不完整"：检索命中某 chunk 后，按 {@code metadata.outline_path} 把同章节、
 * {@code chunk_index} 相邻的兄弟 chunk 一起拉进上下文，保证整节内容完整输出。
 * <p>
 * 例：命中"分布式限流" → 自动带上同章节的"为什么需要 MinerU / 异步解析流程 / 结果解包"。
 * <p>
 * 实现要点：
 * <ul>
 *   <li>按 outline_path 聚合命中 chunk 的 index 范围，同章节只查一次 DB（窗口 = [min-window, max+window]）</li>
 *   <li>窗口式扩展而非整章全拉，控制上下文体积；max-total 兜底截断</li>
 *   <li>结果按 chunk_index 排序，还原阅读顺序</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SiblingExpansionPostProcessor implements SearchResultPostProcessor {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Value("${spring.ai.vectorstore.pgvector.table-name:spring_ai_store_vector}")
    private String vectorTable;

    /** 同章节前后各扩展的兄弟数（窗口半径） */
    @Value("${rag.search.sibling.window:2}")
    private int window;

    /** 扩展后总条数上限（避免上下文爆炸） */
    @Value("${rag.search.sibling.max-total:10}")
    private int maxTotal;

    @Override
    public String getName() {
        return "sibling-expansion";
    }

    @Override
    public int getOrder() {
        return 0; // sibling-expansion(0) → dedup(1) → fusion(5) → rerank(10)
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        // 已禁用：兄弟扩展会拉入弱相关的同章节 chunk，稀释精排后的召回质量，当前不启用。
        return false;
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                         List<SearchChannelResult> results,
                                         SearchContext context) {
        if (chunks == null || chunks.isEmpty()) {
            return chunks;
        }

        Set<String> seen = new HashSet<>();
        LinkedHashMap<String, RetrievedChunk> merged = new LinkedHashMap<>();
        for (RetrievedChunk hit : chunks) {
            putIfNew(hit, seen, merged);
        }

        // 按 outline_path 聚合命中的 chunk_index 范围（同章节只查一次）
        Map<String, int[]> ranges = new HashMap<>();
        for (RetrievedChunk hit : chunks) {
            String outline = stringMeta(hit, "outline_path");
            Integer idx = intMeta(hit, "chunk_index");
            if (outline == null || outline.isBlank() || idx == null) {
                continue;
            }
            ranges.merge(outline, new int[]{idx, idx},
                    (a, b) -> new int[]{Math.min(a[0], b[0]), Math.max(a[1], b[1])});
        }

        for (Map.Entry<String, int[]> e : ranges.entrySet()) {
            int[] r = e.getValue();
            int w = effectiveWindow(e.getKey());
            for (RetrievedChunk sib : querySiblings(e.getKey(), r[0] - w, r[1] + w)) {
                putIfNew(sib, seen, merged);
            }
        }

        // 不再按 chunk_index 全局重排：命中 chunk 保持检索相关度序（向量通道为 similarity DESC），
        // 同章节兄弟块按 chunk_index 升序紧跟其后。最终排序交给 Fusion/Rerank，避免此处用文档
        // 阅读顺序反向打乱真实相关度。
        List<RetrievedChunk> out = new ArrayList<>(merged.values());
        if (out.size() > maxTotal) {
            out = new ArrayList<>(out.subList(0, maxTotal));
        }

        log.info("[兄弟扩展] 输入={}条, 输出={}条 (window={}, maxTotal={})",
                chunks.size(), out.size(), window, maxTotal);
        return out;
    }

    private void putIfNew(RetrievedChunk c, Set<String> seen, Map<String, RetrievedChunk> merged) {
        if (c == null) {
            return;
        }
        String key = keyOf(c.getContent());
        if (seen.add(key)) {
            merged.put(key, c);
        }
    }

    /**
     * 按章节深度自适应窗口：子章节(深度≥2，如"文档 > 组件三")正常扩展同章节兄弟；
     * 顶级章节(深度=1，下一层 chunk 很多)缩小窗口，避免跨子主题拉太多导致上下文爆炸。
     */
    private int effectiveWindow(String outlinePath) {
        int depth = outlinePath == null ? 1 : outlinePath.split(" > ").length;
        return depth >= 2 ? window : Math.min(window, 1);
    }

    /**
     * 查同 outline_path 且 chunk_index 落在 [from,to] 的兄弟（含命中自身），按 index 升序
     */
    private List<RetrievedChunk> querySiblings(String outline, int from, int to) {
        if (from < 0) {
            from = 0;
        }
        String sql = "SELECT content, metadata::text AS metadata FROM " + vectorTable
                + " WHERE metadata->>'outline_path' = ?"
                + " AND (metadata->>'chunk_index')::int BETWEEN ? AND ?"
                + " ORDER BY (metadata->>'chunk_index')::int";
        try {
            return jdbcTemplate.query(sql, (rs, i) -> {
                Map<String, Object> md = parseMeta(rs.getString("metadata"));
                return new RetrievedChunk(rs.getString("content"), null, md, SearchChannelType.VECTOR);
            }, outline, from, to);
        } catch (Exception e) {
            log.warn("[兄弟扩展] 查询同章节兄弟失败 outline={}: {}", outline, e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMeta(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String stringMeta(RetrievedChunk c, String key) {
        if (c == null || c.getMetadata() == null) {
            return null;
        }
        Object v = c.getMetadata().get(key);
        return v == null ? null : v.toString();
    }

    private Integer intMeta(RetrievedChunk c, String key) {
        String s = stringMeta(c, key);
        if (s == null) {
            return null;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String keyOf(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= 64 ? content : content.substring(0, 64);
    }
}
