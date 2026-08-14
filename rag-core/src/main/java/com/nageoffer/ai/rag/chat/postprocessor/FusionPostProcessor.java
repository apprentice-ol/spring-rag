package com.nageoffer.ai.rag.chat.postprocessor;

import cn.hutool.crypto.digest.DigestUtil;
import com.nageoffer.ai.rag.chat.retrieval.RetrievedChunk;
import com.nageoffer.ai.rag.chat.retrieval.SearchChannelResult;
import com.nageoffer.ai.rag.chat.retrieval.SearchChannelType;
import com.nageoffer.ai.rag.chat.retrieval.SearchContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.nageoffer.ai.obs.observation.annotation.ObservedStep;

/**
 * RRF（Reciprocal Rank Fusion）融合后处理器（责任链第二环）。
 * <p>
 * 将多通道检索结果按 RRF 算法融合排序：对出现在多个通道中的 chunk 加权计分，
 * 通道内排名越靠前权重越高。支持各通道配置不同权重。
 * </p>
 *
 * <p>RRF 评分公式：score(c) = Σᵢ wᵢ / (k + rᵢ(c) + 1)</p>
 * <ul>
 *   <li>rᵢ(c) — chunk c 在通道 i 中的排名（1-based）</li>
 *   <li>wᵢ — 通道 i 的权重</li>
 *   <li>k — 平滑常数（默认 60）</li>
 * </ul>
 */
@Slf4j
@Component
public class FusionPostProcessor implements SearchResultPostProcessor {

    /** RRF 平滑常数 k */
    @Value("${rag.search.fusion.rrf-k:60}")
    private int rrfK;

    @Override
    public String getName() {
        return "fusion";
    }

    @Override
    public int getOrder() {
        return 5;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return true;
    }

    @Override
    @ObservedStep("rag.postproc")
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                         List<SearchChannelResult> results,
                                         SearchContext context) {
        if (results.size() <= 1) {
            log.info("[RRF融合] 单通道（{} 个），跳过融合", results.size());
            return chunks;
        }

        // 1. 建立内容 -> 原始 chunk 的映射（去重键使用全文 SHA-256，与 DeduplicationPostProcessor 一致）；
        //    同内容多通道命中时，保留原始相似度(originalScore)最高的那个，便于后续展示真实相关度
        Map<String, RetrievedChunk> contentMap = new LinkedHashMap<>();
        Map<String, Double> bestOriginal = new HashMap<>();
        for (RetrievedChunk chunk : chunks) {
            String key = keyOf(chunk.getContent());
            double orig = chunk.getOriginalScore() != null ? chunk.getOriginalScore()
                    : (chunk.getScore() != null ? chunk.getScore() : 0.0);
            if (!contentMap.containsKey(key) || orig > bestOriginal.getOrDefault(key, 0.0)) {
                contentMap.put(key, chunk);
                bestOriginal.put(key, orig);
            }
        }

        // 2. 为每个通道建立 内容->排名 映射
        Map<String, Map<String, Integer>> channelRanks = new HashMap<>();
        for (SearchChannelResult channelResult : results) {
            String channelName = channelResult.getChannelName();
            Map<String, Integer> rankMap = new HashMap<>();
            List<RetrievedChunk> channelChunks = channelResult.getChunks();
            for (int i = 0; i < channelChunks.size(); i++) {
                String k = keyOf(channelChunks.get(i).getContent());
                if (!rankMap.containsKey(k)) {
                    rankMap.put(k, i + 1); // 1-based rank
                }
            }
            channelRanks.put(channelName, rankMap);
        }

        // 3. 计算 RRF 分数
        Map<String, Double> rrfScores = new HashMap<>();
        for (String contentKey : contentMap.keySet()) {
            double score = 0;
            for (SearchChannelResult channelResult : results) {
                Map<String, Integer> ranks = channelRanks.get(channelResult.getChannelName());
                if (ranks != null && ranks.containsKey(contentKey)) {
                    int rank = ranks.get(contentKey);
                    double weight = getChannelWeight(channelResult.getChannelType());
                    score += weight / (rrfK + rank);
                }
            }
            rrfScores.put(contentKey, score);
        }

        // 4. 按 RRF 分数降序排列
        List<RetrievedChunk> fused = contentMap.values().stream()
                .sorted(Comparator.<RetrievedChunk, Double>comparing(
                        c -> rrfScores.get(keyOf(c.getContent())), Comparator.reverseOrder())
                        .thenComparing(c -> c.getScore() != null ? c.getScore() : 0, Comparator.reverseOrder()))
                .collect(Collectors.toList());

        // 5. 更新 score 为 RRF 融合分；originalScore 保留最高原始相似度（展示用，不受 RRF 影响）
        for (RetrievedChunk chunk : fused) {
            String key = keyOf(chunk.getContent());
            chunk.setScore(rrfScores.get(key));
            chunk.setOriginalScore(bestOriginal.get(key));
        }

        // 6. 按 budget 的 candidateLimit 截断
        int limit = context.getBudget() != null
                ? context.getBudget().getCandidateLimit() : fused.size();
        if (fused.size() > limit) {
            fused = fused.subList(0, limit);
        }

        return fused;
    }

    private String keyOf(String content) {
        return DigestUtil.sha256Hex(content == null ? "" : content);
    }

    /**
     * 获取通道权重。可根据通道类型差异化配置。
     */
    private double getChannelWeight(SearchChannelType type) {
        return switch (type) {
            case VECTOR -> 1.0;
            case KEYWORD -> 0.8;
            case GRAPH -> 0.5;
            case WEB_SEARCH -> 0.4;
        };
    }
}
