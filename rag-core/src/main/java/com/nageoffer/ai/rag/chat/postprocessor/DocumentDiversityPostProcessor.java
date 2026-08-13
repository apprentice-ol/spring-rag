package com.nageoffer.ai.rag.chat.postprocessor;

import com.nageoffer.ai.rag.chat.retrieval.RetrievedChunk;
import com.nageoffer.ai.rag.chat.retrieval.SearchChannelResult;
import com.nageoffer.ai.rag.chat.retrieval.SearchContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * MMR 多样性后处理器（已禁用）。
 * <p>
 * 【2026-07-31 禁用原因】
 * RerankPostProcessor 的 balanceDocQuota() 已保证每篇文档至少 max(2, topK/docCount) 条，
 * 多文档覆盖已有基本保障。MMR 在此基础上再做多样性，会把 Rerank 高分段 chunk 换成低分段
 * 的"其他文档"chunk，反而降低上下文质量——且 MMR 的 Jaccard 相似度（词袋级）无法理解语义，
 * 同一方法的不同代码段因关键词重叠被判为"极度相似"而被降权，实际它们提供的是互补信息。
 * </p>
 * <p>
 * 多文档多样性应交给检索层（图检索、查询扩展、多通道），而非在后处理层做语义牺牲。
 * 如需临时启用，将 isEnabled 返回 true 即可。
 * </p>
 *
 * @see <a href="https://dev.to/gabrielanhaia/why-your-vector-index-returns-five-copies-of-the-same-doc">Why Your Vector Index Returns Five Copies of the Same Doc</a>
 */
@Slf4j
@Component
public class DocumentDiversityPostProcessor implements SearchResultPostProcessor {

    /** MMR λ：0.7 偏向相关性，0.3 偏向多样性 */
    private static final double LAMBDA = 0.7;

    @Override
    public String getName() { return "mmr-diversity"; }

    @Override
    public int getOrder() { return 15; }  // 在 Rerank(10) 之后：先用 Rerank 语义分排序，再基于 Rerank 分做多样性选取

    @Override
    public boolean isEnabled(SearchContext context) { return false; }  // 已禁用：balanceDocQuota 已覆盖多文档需求，MMR 反而会牺牲相关性

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                         List<SearchChannelResult> results,
                                         SearchContext context) {
        if (chunks == null || chunks.size() <= 2) return chunks;

        List<RetrievedChunk> candidates = new ArrayList<>(chunks);
        List<RetrievedChunk> selected = new ArrayList<>();
        Set<String> selectedDocs = new HashSet<>();

        while (!candidates.isEmpty()) {
            RetrievedChunk best = null;
            double bestMmr = Double.NEGATIVE_INFINITY;
            for (RetrievedChunk c : candidates) {
                // 算出得分
                double relevance = score(c);
                double maxSim = maxSimilarity(c, selected);
                double mmr = LAMBDA * relevance - (1 - LAMBDA) * maxSim;
                if (mmr > bestMmr) {
                    bestMmr = mmr;
                    best = c;
                }
            }
            if (best == null) break;
            selected.add(best);
            selectedDocs.add(docIdOf(best));
            candidates.remove(best);
        }

        if (selectedDocs.size() > 1) {
            log.info("[MMR多样性] 输入={}条, 输出={}条, 涉及{}篇文档, λ={}",
                    chunks.size(), selected.size(), selectedDocs.size(), LAMBDA);
        }

        return selected;
    }

    /** Rerank 相关分（归一化到 0~1），缺失时用 0 */
    private static double score(RetrievedChunk c) {
        Double s = c.getOriginalScore();
        if (s == null) {
            s = c.getScore();
        }
        return s == null ? 0.0 : Math.clamp(s, 0, 1);
    }

    /** 与已选集合的最大 Jaccard 相似度（词袋级），同一文档直接判 1 */
    private static double maxSimilarity(RetrievedChunk c, List<RetrievedChunk> selected) {
        if (selected.isEmpty()) {
            return 0;
        }
        double max = 0;
        Set<String> cTokens = tokenize(c.getContent());
        for (RetrievedChunk s : selected) {
            // 同一文档：高相似度，强烈降权
            String cDoc = docIdOf(c);
            String sDoc = docIdOf(s);
            if (cDoc.equals(sDoc)) {
                // 最高惩罚，直接返回
                return 1.0;
            }
            double jacc = jaccard(cTokens, tokenize(s.getContent()));
            if (jacc > max) max = jacc;
        }
        return max;
    }



    private static Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null){
            return tokens;
        }
        for (String w : text.toLowerCase().split("[^\\p{L}\\p{N}]+")) {
            if (!w.isBlank() && w.length() > 1){
                tokens.add(w);
            }
        }
        return tokens;
    }


    /**
     * 计算两个集合的 Jaccard 相似度
     * @param a
     * @param b
     * @return
     */
    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 0;
        Set<String> intersect = new HashSet<>(a);
        intersect.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0 : (double) intersect.size() / union.size();
    }

    private static String docIdOf(RetrievedChunk c) {
        if (c.getMetadata() == null) {
            return "_?";
        }
        Object v = c.getMetadata().get("doc_id");
        return v == null ? "_?" : v.toString();
    }
}
