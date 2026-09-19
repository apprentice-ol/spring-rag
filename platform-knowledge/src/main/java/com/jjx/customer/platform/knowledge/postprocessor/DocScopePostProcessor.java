package com.jjx.customer.platform.knowledge.postprocessor;

import com.jjx.customer.platform.knowledge.retrieval.RetrievedChunk;
import com.jjx.customer.platform.knowledge.retrieval.SearchChannelResult;
import com.jjx.customer.platform.knowledge.retrieval.SearchChannelType;
import com.jjx.customer.platform.knowledge.retrieval.SearchContext;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import com.jjx.ai.llmobservability.observation.annotation.TelemetryStep;

/**
 * 文档作用域收敛后处理器（dedup 之后、fusion 之前）。
 * <p>
 * 对齐 ragent 的「KB 意图 → 库作用域」收敛：本项目的轻量等价物是「关键词通道命中 → 目标文档白名单」。
 * 关键词通道命中意味着该 chunk 的 keywords / 文档名与查询词有精确证据，可信为检索目标文档；
 * 以此为白名单，把其他通道（向量）里<b>非白名单文档</b>的块过滤掉，阻断无关文档
 * （如发票查询时被低相似度召回的 API 字段文档）混入 RRF 候选池。
 * </p>
 * <p>无关键词命中时不动（退化回纯向量检索），避免误伤。</p>
 */
@Slf4j
@Component
public class DocScopePostProcessor implements SearchResultPostProcessor {

    @Override
    public String getName() {
        return "doc-scope";
    }

    @Override
    public int getOrder() {
        return 2; // dedup(1) → doc-scope(2) → fusion(5) → rerank(10)
    }

    /**
     * 【2026-09-18 禁用】对齐 agent-framework（无此机制，检索效果反而更好）。
     *
     * <p>禁用原因：关键词通道（bm25 模式）用 2 字滑窗 OR 语义召回，命中本来就宽而噪。
     * 把它当白名单意味着<b>只要 BM25 捞到一篇边缘文档，向量通道召回的那几篇正确文档
     * 就会被整片丢掉</b>——多通道融合退化成"只在 BM25 命中的文档里挑"，
     * 而 BM25 恰恰是两路里更不可信的一路。跨文档的取舍应当交给 RRF 与 Rerank 按分数决定，
     * 而不是由一个二值白名单提前砍掉。</p>
     *
     * <p>保留实现以备需要时恢复（改回 {@code return true;}）。</p>
     */
    @Override
    public boolean isEnabled(SearchContext context) {
        return false;
    }

    @Override
    @TelemetryStep("rag.postproc")
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                         List<SearchChannelResult> results,
                                         SearchContext context) {
        if (chunks == null || chunks.isEmpty()) {
            return chunks;
        }

        // 关键词通道命中即 doc 路由信号：取其 doc_id 集合作为目标文档白名单
        Set<String> routedDocs = results.stream()
                .filter(r -> r.getChannelType() == SearchChannelType.KEYWORD)
                .flatMap(r -> r.getChunks().stream())
                .map(DocScopePostProcessor::docIdOf)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (routedDocs.isEmpty()) {
            log.debug("[文档作用域] 关键词通道无命中，不做收敛（退化纯向量）");
            return chunks;
        }

        // 过滤非白名单文档的块；关键词通道自身命中无条件保留（其 doc 必在白名单内）
        List<RetrievedChunk> filtered = chunks.stream()
                .filter(c -> c.getChannelType() == SearchChannelType.KEYWORD
                        || routedDocs.contains(docIdOf(c)))
                .toList();

        int dropped = chunks.size() - filtered.size();
        if (dropped > 0) {
            log.info("[文档作用域] 白名单 {} 篇文档, 过滤掉其他文档 {} 条（{} → {}）",
                    routedDocs.size(), dropped, chunks.size(), filtered.size());
        } else {
            log.debug("[文档作用域] 白名单 {} 篇文档, 无跨文档块需过滤", routedDocs.size());
        }
        return filtered;
    }

    private static String docIdOf(RetrievedChunk c) {
        Map<String, Object> meta = c.getMetadata();
        if (meta == null) {
            return null;
        }
        Object v = meta.get("doc_id");
        return v == null ? null : v.toString();
    }
}
