package com.nageoffer.ai.rag.chat.postprocessor;

import com.jjx.ai.llmobservability.observation.annotation.TelemetryStep;
import com.nageoffer.ai.rag.chat.rerank.RerankClient;
import com.nageoffer.ai.rag.chat.retrieval.RetrievedChunk;
import com.nageoffer.ai.rag.chat.retrieval.SearchChannelResult;
import com.nageoffer.ai.rag.chat.retrieval.SearchContext;
import com.nageoffer.ai.rag.config.properties.ChatProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Rerank 精排处理器（责任链第三环）。
 * <p>
 * 将融合后的候选列表送入 Rerank API 进行精排，按 query 相关性重新排序，
 * 并截取 topK 条作为最终结果。仅在配置了 RerankClient 时启用。
 *
 * <p>对齐 ragent 的纯精排语义：不做「按文档补配额」——
 * 实测 balanceDocQuota 会在噪声文档进 TopK 时把它强行拉满 minQuota 条（2 文档、TopK=8 时
 * 噪声从 1 条变 4 条），放大召回侧污染。文档完整性由 Rerank 分数自然决定，需要多文档
 * 均衡可启用 {@link DocumentDiversityPostProcessor}（MMR，在 Rerank 之后按分数做多样性）。</p>
 */
@Slf4j
@Component
@ConditionalOnBean(RerankClient.class)
public class RerankPostProcessor implements SearchResultPostProcessor {

    private final RerankClient rerankClient;
    private final ChatProperties chatProperties;

    public RerankPostProcessor(List<RerankClient> rerankClients, ChatProperties chatProperties) {
        // 优先使用非 noop 的客户端
        this.rerankClient = rerankClients.stream()
                .filter(c -> !"noop".equals(c.provider()))
                .findFirst()
                .orElse(rerankClients.isEmpty() ? null : rerankClients.getFirst());
        this.chatProperties = chatProperties;
    }

    @Override
    public String getName() {
        return "rerank";
    }

    @Override
    public int getOrder() {
        return 10;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return rerankClient != null;
    }

    @Override
    @TelemetryStep("rag.postproc")
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                         List<SearchChannelResult> results,
                                         SearchContext context) {
        if (chunks == null || chunks.isEmpty() || rerankClient == null) {
            return chunks;
        }

        String query = context.getRewrittenQuery() != null
                ? context.getRewrittenQuery() : context.getQuery();

        int topK = context.getBudget() != null
                ? context.getBudget().getContextTopK()
                : context.getTopK();

        long t0 = System.currentTimeMillis();
        List<RetrievedChunk> reranked = rerankClient.rerank(query, chunks, topK);

        // 阈值过滤（可选，rag.chat.rerank-score-threshold=0 关闭）：精排后低相关块不进上下文，
        // 宁喂少量精品不喂掺沙——top5 里 4/5 无关块会稀释注意力（liveRag eval precision@5≈0.22 的教训）
        double threshold = chatProperties.getRerankScoreThreshold();
        List<RetrievedChunk> filtered = reranked;
        if (threshold > 0) {
            filtered = reranked.stream()
                    .filter(c -> c.getScore() == null || c.getScore() >= threshold)
                    .toList();
            if (filtered.size() < reranked.size()) {
                log.info("[Rerank] 阈值过滤({}): {} → {} 条", threshold, reranked.size(), filtered.size());
            }
        }

        log.info("[Rerank] 输入={}, 输出={}, 耗时={}ms", chunks.size(), filtered.size(),
                System.currentTimeMillis() - t0);

        return filtered;
    }
}
