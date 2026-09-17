package com.jjx.customer.platform.eval.framework.scorer;

import com.jjx.customer.platform.eval.framework.EvalSample;
import com.jjx.customer.platform.eval.framework.EvalScore;
import com.jjx.customer.platform.eval.framework.EvalScorer;
import com.jjx.customer.platform.eval.metrics.MetricScorer;
import com.jjx.customer.platform.eval.metrics.MrrScorer;
import com.jjx.customer.platform.eval.metrics.RecallAtKScorer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * doc 集指标适配器单测：包装后输出与 MetricScorer 直调完全一致（落库口径不变的守护测试）。
 */
class DocSetScorerAdapterTest {

    @Test
    void 适配器输出与直调一致() {
        MetricScorer recall = new RecallAtKScorer(5);
        MetricScorer mrr = new MrrScorer();
        List<String> retrieved = List.of("a", "b", "c");
        List<String> expected = List.of("b", "d");

        EvalScorer adapterR = new DocSetScorerAdapter(recall);
        EvalScorer adapterM = new DocSetScorerAdapter(mrr);

        EvalSample sample = EvalSample.builder()
                .itemId(1L)
                .retrievedDocIds(retrieved)
                .expectedDocIds(expected)
                .build();

        List<EvalScore> r = adapterR.score(sample);
        assertEquals(1, r.size());
        assertEquals(recall.name(), r.get(0).name());
        assertEquals(recall.score(retrieved, expected), r.get(0).value(), 1e-12);

        List<EvalScore> m = adapterM.score(sample);
        assertEquals(mrr.score(retrieved, expected), m.get(0).value(), 1e-12);
        assertEquals("mrr", m.get(0).name());
    }

    @Test
    void 错误样本跳过() {
        EvalScorer adapter = new DocSetScorerAdapter(new MrrScorer());
        List<EvalScore> scores = adapter.score(EvalSample.builder().itemId(1L).error("boom").build());
        assertTrue(scores.isEmpty());
    }
}
