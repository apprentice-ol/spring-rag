package com.nageoffer.ai.rag.eval.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 检索指标打分器单测（纯算法，无 Spring 上下文）。 */
class MetricScorerTest {

    private static final double DELTA = 0.001;

    // retrieved 顺序按相关度降序；expected = ground truth doc_ids
    private final List<String> retrieved = List.of("d3", "d1", "d2");
    private final List<String> expected = List.of("d1", "d2");

    @Test
    void recallAtK() {
        assertEquals(1.0, new RecallAtKScorer(3).score(retrieved, expected), DELTA);  // 前 3 条命中 d1,d2 → 2/2
        assertEquals(0.5, new RecallAtKScorer(2).score(retrieved, expected), DELTA);  // 前 2 条 [d3,d1] 命中 d1 → 1/2
        assertEquals(0.0, new RecallAtKScorer(3).score(List.of("x", "y"), expected), DELTA);
    }

    @Test
    void precisionAtK() {
        assertEquals(2.0 / 3, new PrecisionAtKScorer(3).score(retrieved, expected), DELTA); // 2/3
        assertEquals(0.5, new PrecisionAtKScorer(2).score(retrieved, expected), DELTA);     // 前 2 条命中 1 → 1/2
        assertEquals(0.0, new PrecisionAtKScorer(3).score(List.of("x", "y"), expected), DELTA);
    }

    @Test
    void mrr() {
        assertEquals(0.5, new MrrScorer().score(retrieved, expected), DELTA);               // d1 在第 2 位 → 1/2
        assertEquals(1.0, new MrrScorer().score(List.of("d1", "d2"), expected), DELTA);     // 首位命中
        assertEquals(0.0, new MrrScorer().score(List.of("x", "y"), expected), DELTA);       // 无命中
    }

    @Test
    void ndcg() {
        // DCG = 1/log2(3) + 1/log2(4) = 1.1309；IDCG = 1/log2(2) + 1/log2(3) = 1.6309 → 0.6934
        assertEquals(0.6934, new NdcgScorer(3).score(retrieved, expected), DELTA);
        assertEquals(1.0, new NdcgScorer(3).score(List.of("d1", "d2"), expected), DELTA);   // 完美排序
        assertEquals(0.0, new NdcgScorer(3).score(List.of("x", "y"), expected), DELTA);
    }

    @Test
    void expectedEmpty() {
        // expected 为空：recall 视为平凡满足（1.0）；mrr 无定义（0.0）
        assertEquals(1.0, new RecallAtKScorer(3).score(retrieved, List.of()), DELTA);
        assertEquals(0.0, new MrrScorer().score(retrieved, List.of()), DELTA);
    }
}
