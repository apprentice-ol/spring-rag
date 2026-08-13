package com.nageoffer.ai.rag.eval.metrics;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Recall@k：前 k 条召回中命中的期望文档数 / 期望文档总数。
 *
 * <p>期望为空时返回 1.0（没有需要召回的文档，视为平凡满足）。</p>
 */
public class RecallAtKScorer implements MetricScorer {

    private final int k;

    public RecallAtKScorer(int k) {
        this.k = k;
    }

    @Override
    public String name() {
        return "recall_at_" + k;
    }

    @Override
    public double score(List<String> retrievedDocIds, List<String> expectedDocIds) {
        if (expectedDocIds == null || expectedDocIds.isEmpty()) {
            return 1.0;
        }
        Set<String> expected = new HashSet<>(expectedDocIds);
        int limit = Math.min(k, retrievedDocIds == null ? 0 : retrievedDocIds.size());
        long hit = 0;
        for (int i = 0; i < limit; i++) {
            if (expected.contains(retrievedDocIds.get(i))) {
                hit++;
            }
        }
        return (double) hit / expected.size();
    }

    public int getK() {
        return k;
    }
}
