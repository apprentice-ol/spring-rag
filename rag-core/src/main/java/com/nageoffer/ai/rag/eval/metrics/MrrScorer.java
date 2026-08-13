package com.nageoffer.ai.rag.eval.metrics;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MRR（Mean Reciprocal Rank 的单条版）：首位命中期望文档的倒数排名。
 *
 * <p>retrieved 第 i 位（0-based）首次命中 → 1/(i+1)；无命中或期望为空 → 0。</p>
 */
public class MrrScorer implements MetricScorer {

    @Override
    public String name() {
        return "mrr";
    }

    @Override
    public double score(List<String> retrievedDocIds, List<String> expectedDocIds) {
        if (expectedDocIds == null || expectedDocIds.isEmpty()) {
            return 0.0;
        }
        Set<String> expected = new HashSet<>(expectedDocIds);
        if (retrievedDocIds == null) {
            return 0.0;
        }
        for (int i = 0; i < retrievedDocIds.size(); i++) {
            if (expected.contains(retrievedDocIds.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }
}
