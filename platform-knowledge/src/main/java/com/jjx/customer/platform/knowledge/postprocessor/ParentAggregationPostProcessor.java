package com.jjx.customer.platform.knowledge.postprocessor;

import com.jjx.customer.platform.knowledge.retrieval.RetrievedChunk;
import com.jjx.customer.platform.knowledge.retrieval.SearchChannelResult;
import com.jjx.customer.platform.knowledge.retrieval.SearchContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.jjx.ai.llmobservability.observation.annotation.TelemetryStep;

/**
 * 父块聚合后处理器（rerank 之后，责任链末端）——小块检索、大块生成的出口。
 *
 * <p><b>为什么在 rerank 之后</b>：打分排序发生在子块级（语义集中、分数区分度高，
 * 见 plan/2026-09-18-small-to-big-retrieval.md 的对照实验：期望内容平均 +0.103），
 * 交叉编码器吃的是子块短文本而非父块长文；聚合只是把同一父块的多个子块命中折叠成
 * 一个父块席位（父分 = max 子块分），并把内容换回父块原文供生成侧组装。</p>
 *
 * <p><b>同文档挤占的天然解</b>：多个子块命中同一父块只占一个最终席位——子块化后
 * 40 候选里的同文档扎堆被折叠，topK 能覆盖更多不同文档（recall-optimization 计划
 * 第 2 步的细粒度实现）。</p>
 *
 * <p><b>自门控（灰度安全）</b>：块无 {@code parent_key}（存量未重灌语料 / child-chunk
 * 关闭期入库）一律直通——新旧数据可混合存在，代码先上、数据后灌、按篇生效。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ParentAggregationPostProcessor implements SearchResultPostProcessor {

    private final JdbcTemplate jdbcTemplate;

    /** 聚合开关（关 = 子块直出，上下文变碎但可用；重灌即恢复） */
    @Value("${rag.search.parent.enabled:true}")
    private boolean enabled;

    /** 父块 metadata 里携带的命中小块上限（子块分数明细进轨迹用） */
    @Value("${rag.search.parent.max-children-cited:3}")
    private int maxChildrenCited;

    /** 单次父块内容批量取回的 IN 上限（防御性：rerank 输出量级 << 该值） */
    private static final int LOOKUP_BATCH = 100;

    @Override
    public String getName() {
        return "parent-aggregation";
    }

    @Override
    public int getOrder() {
        return 11; // dedup(1) → fusion(5) → rerank(10) → parent-aggregation(11)
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return enabled;
    }

    @Override
    @TelemetryStep("rag.postproc")
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        // 注意：单条也要处理——折叠是"多子合一"，但内容替换（子块→父块原文）对单条同样必要，
        // 早退会让 LLM 只拿到 400 字片段而不是父块上下文（实测：只回填 7 篇时该题恰好只命中 1 条）。
        if (chunks == null || chunks.isEmpty()) {
            return chunks;
        }

        // 分流：带 parent_key 的子块聚合；不带的（存量数据 / 未展开小父块）原样直通
        Map<String, List<RetrievedChunk>> byParent = new LinkedHashMap<>();
        List<RetrievedChunk> passthrough = new ArrayList<>();
        for (RetrievedChunk chunk : chunks) {
            Object key = chunk.getMetadata() == null ? null : chunk.getMetadata().get("parent_key");
            if (key instanceof String parentKey && !parentKey.isBlank()) {
                byParent.computeIfAbsent(parentKey, k -> new ArrayList<>()).add(chunk);
            } else {
                passthrough.add(chunk);
            }
        }
        if (byParent.isEmpty()) {
            return chunks; // 全是存量块，零行为变化
        }

        Map<String, String> parentContents = lookupParentContents(byParent.keySet());
        List<RetrievedChunk> aggregated = new ArrayList<>(byParent.size());
        int folded = 0;
        for (Map.Entry<String, List<RetrievedChunk>> entry : byParent.entrySet()) {
            List<RetrievedChunk> children = entry.getValue();
            folded += children.size() - 1;
            aggregated.add(toParentChunk(entry.getKey(), children,
                    parentContents.getOrDefault(entry.getKey(), bestContent(children))));
        }

        // 父块 + 直通块合流，按分数降序稳定排序（父块分数=max 子块分，与子块序可比）
        List<RetrievedChunk> merged = new ArrayList<>(aggregated.size() + passthrough.size());
        merged.addAll(aggregated);
        merged.addAll(passthrough);
        merged.sort(Comparator.comparingDouble((RetrievedChunk c) ->
                c.getScore() == null ? 0.0 : c.getScore()).reversed());

        log.info("[父块聚合] 子块 {} 条 → 父块 {} 条（折叠 {} 条；直通存量块 {} 条）",
                chunks.size(), aggregated.size(), folded, passthrough.size());
        return merged;
    }

    /**
     * 父块代表块：内容 = 父块原文（取不到时退回子块最优内容，绝不丢命中），
     * 分数 = 子块最高 rerank 分，originalScore = 子块最高通道原始分（展示口径不变），
     * metadata 继承最优子块（doc_id/outline_path 等引用字段）+ 命中明细。
     */
    private RetrievedChunk toParentChunk(String parentKey, List<RetrievedChunk> children, String parentContent) {
        RetrievedChunk best = children.stream()
                .max(Comparator.comparingDouble(c -> c.getScore() == null ? 0.0 : c.getScore()))
                .orElse(children.getFirst());
        Map<String, Object> meta = new HashMap<>(best.getMetadata() == null ? Map.of() : best.getMetadata());
        meta.put("parent_key", parentKey);
        meta.put("parent", true);
        meta.put("child_hits", children.size());
        meta.put("child_scores", children.stream()
                .map(c -> c.getScore() == null ? 0.0 : Math.round(c.getScore() * 10000.0) / 10000.0)
                .sorted(Comparator.reverseOrder())
                .limit(Math.max(1, maxChildrenCited))
                .toList());

        RetrievedChunk parent = new RetrievedChunk(parentContent,
                best.getScore(), meta, best.getChannelType());
        parent.setOriginalScore(children.stream()
                .map(RetrievedChunk::getOriginalScore)
                .filter(v -> v != null)
                .max(Double::compareTo)
                .orElse(best.getOriginalScore()));
        return parent;
    }

    /** 批量取父块原文（IN 分批；缺失的父块由调用方回退子块内容）。 */
    private Map<String, String> lookupParentContents(java.util.Set<String> parentKeys) {
        Map<String, String> contents = new HashMap<>();
        List<String> keys = new ArrayList<>(parentKeys);
        for (int from = 0; from < keys.size(); from += LOOKUP_BATCH) {
            List<String> batch = keys.subList(from, Math.min(from + LOOKUP_BATCH, keys.size()));
            String placeholders = String.join(",", java.util.Collections.nCopies(batch.size(), "?"));
            try {
                jdbcTemplate.query(
                        "SELECT parent_key, content FROM sa_chunk_parent WHERE parent_key IN (" + placeholders + ")",
                        rs -> {
                            while (rs.next()) {
                                contents.put(rs.getString("parent_key"), rs.getString("content"));
                            }
                        },
                        batch.toArray());
            } catch (Exception e) {
                log.warn("[父块聚合] 父块原文取回失败（回退子块内容）: {}", e.getMessage());
            }
        }
        return contents;
    }

    /** 父块原文缺失时的兜底内容：子块里分最高的那个的原文。 */
    private static String bestContent(List<RetrievedChunk> children) {
        return children.stream()
                .max(Comparator.comparingDouble(c -> c.getScore() == null ? 0.0 : c.getScore()))
                .map(RetrievedChunk::getContent)
                .orElse(children.getFirst().getContent());
    }
}
