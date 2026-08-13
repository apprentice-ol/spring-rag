package com.nageoffer.ai.rag.eval.metrics;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * nDCG@k（Normalized Discounted Cumulative Gain，二值相关性）。
 *
 * <p>DCG@k = Σ_{i=1..k} rel_i / log2(i+1)，rel_i ∈ {0,1}；
 * IDCG@k = 前 min(|expected|,k) 位全相关；nDCG = DCG / IDCG。</p>
 */
public class NdcgScorer implements MetricScorer {

    private static final double LOG2 = Math.log(2);

    private final int k;

    public NdcgScorer(int k) {
        this.k = k;
    }

    @Override
    public String name() {
        return "ndcg_at_" + k;
    }

    @Override
    public double score(List<String> retrievedDocIds, List<String> expectedDocIds) {
        Set<String> expected = new HashSet<>(expectedDocIds == null ? Set.of() : expectedDocIds);
        int limit = Math.min(k, retrievedDocIds == null ? 0 : retrievedDocIds.size());

        double dcg = 0.0;
        for (int i = 0; i < limit; i++) {
            if (expected.contains(retrievedDocIds.get(i))) {
                dcg += 1.0 / log2(i + 2); // rank = i+1 → log2(rank+1) = log2(i+2)
            }
        }
        int idealHits = Math.min(expected.size(), limit);
        double idcg = 0.0;
        for (int i = 0; i < idealHits; i++) {
            idcg += 1.0 / log2(i + 2);
        }
        return idcg == 0.0 ? 0.0 : dcg / idcg;
    }

    public int getK() {
        return k;
    }

    private static double log2(double x) {
        return Math.log(x) / LOG2;
    }
}
