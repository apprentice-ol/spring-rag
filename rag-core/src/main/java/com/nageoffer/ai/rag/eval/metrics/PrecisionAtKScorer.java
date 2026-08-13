package com.nageoffer.ai.rag.eval.metrics;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Precision@k：前 k 条召回中命中的期望文档数 / k。
 *
 * <p>分母固定为 k（召回不足 k 条时，缺位视为不相关），与 IR 标准定义一致。</p>
 */
public class PrecisionAtKScorer implements MetricScorer {

    private final int k;

    public PrecisionAtKScorer(int k) {
        this.k = k;
    }

    @Override
    public String name() {
        return "precision_at_" + k;
    }

    @Override
    public double score(List<String> retrievedDocIds, List<String> expectedDocIds) {
        Set<String> expected = new HashSet<>(expectedDocIds == null ? Set.of() : expectedDocIds);
        int limit = Math.min(k, retrievedDocIds == null ? 0 : retrievedDocIds.size());
        long hit = 0;
        for (int i = 0; i < limit; i++) {
            if (expected.contains(retrievedDocIds.get(i))) {
                hit++;
            }
        }
        return (double) hit / k;
    }

    public int getK() {
        return k;
    }
}
