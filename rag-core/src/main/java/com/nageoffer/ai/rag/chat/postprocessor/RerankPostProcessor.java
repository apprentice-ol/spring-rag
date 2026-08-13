package com.nageoffer.ai.rag.chat.postprocessor;

import com.nageoffer.ai.rag.chat.rerank.RerankClient;
import com.nageoffer.ai.rag.chat.retrieval.RetrievedChunk;
import com.nageoffer.ai.rag.chat.retrieval.SearchChannelResult;
import com.nageoffer.ai.rag.chat.retrieval.SearchContext;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import com.nageoffer.ai.rag.config.telemetry.TraceStep;

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

    public RerankPostProcessor(List<RerankClient> rerankClients) {
        // 优先使用非 noop 的客户端
        this.rerankClient = rerankClients.stream()
                .filter(c -> !"noop".equals(c.provider()))
                .findFirst()
                .orElse(rerankClients.isEmpty() ? null : rerankClients.get(0));
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
    @TraceStep("rag.postproc")
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

        log.info("[Rerank] 输入={}, 输出={}, 耗时={}ms", chunks.size(), reranked.size(),
                System.currentTimeMillis() - t0);

        return reranked;
    }
}
