package com.jjx.customer.platform.knowledge.postprocessor;

import com.jjx.ai.llmobservability.observation.annotation.TelemetryStep;
import com.jjx.customer.platform.knowledge.rerank.RerankClient;
import com.jjx.customer.platform.knowledge.retrieval.RetrievedChunk;
import com.jjx.customer.platform.knowledge.retrieval.SearchChannelResult;
import com.jjx.customer.platform.knowledge.retrieval.SearchContext;
import com.jjx.customer.platform.config.properties.ChatProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    /**
     * 小块检索超采倍数（父块聚合在后，order 11）：同父多子命中会被折叠成一个席位，
     * rerank 只出 topK 个子块会让最终父块数 < topK（预算被折叠吃掉）。
     * 仅当候选里确有子块（带 parent_key）时生效——存量语料行为零变化。
     */
    @Value("${rag.search.parent.rerank-overfetch:3}")
    private int parentOverfetch;

    @Value("${rag.search.parent.enabled:true}")
    private boolean parentEnabled;

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
        // 子块模式超采：父块聚合会把同父子块折叠成单席位，进量不放大则最终条数 < topK。
        // 超采后请求仍瘦（子块短文本，topK×3 ≈ 12k 字符，远低于衰减阈值）。
        if (parentEnabled && parentOverfetch > 1 && hasChildChunk(chunks)) {
            topK = topK * parentOverfetch;
        }

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

    /** 候选里是否存在子块（带 parent_key 标记）——超采只在子块世界有意义。 */
    private static boolean hasChildChunk(List<RetrievedChunk> chunks) {
        return chunks.stream().anyMatch(c -> c.getMetadata() != null
                && c.getMetadata().get("parent_key") instanceof String key && !key.isBlank());
    }
}
