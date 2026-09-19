package com.jjx.customer.platform.eval.metrics;

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
        // IDCG 的位次上限是 k（理想排序下最多 min(|expected|, k) 条相关），**不是 limit**。
        // 用 limit 会让分母随"实际召回条数"缩水：期望 2 篇、只召回 1 篇且命中时
        // limit=1 → IDCG=1 → nDCG=1.0——漏掉一篇反而拿满分，且召回越少越容易得高分，
        // 指标失去分辨力（实测 run82：期望2/召回1 的用例 nDCG@5 均值 0.833 = 命中率，与召回率 0.417 背离）。
        int idealHits = Math.min(expected.size(), k);
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
