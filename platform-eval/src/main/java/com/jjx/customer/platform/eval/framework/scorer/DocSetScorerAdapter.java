package com.jjx.customer.platform.eval.framework.scorer;

import com.jjx.customer.platform.eval.framework.EvalSample;
import com.jjx.customer.platform.eval.framework.EvalScore;
import com.jjx.customer.platform.eval.framework.EvalScorer;
import com.jjx.customer.platform.eval.metrics.MetricScorer;

import java.util.List;

/**
 * 文档集指标适配器：把既有 {@link MetricScorer}（doc_id 集合纯函数：recall/precision/mrr/ndcg）
 * 接入 {@link EvalScorer} 体系——指标数学零改动，落库口径不变。实例由
 * {@code EvalScorerRegistry} 按 rag.eval.ks 配置组装（非 Spring bean）。
 */
public class DocSetScorerAdapter implements EvalScorer {

    private final MetricScorer delegate;

    public DocSetScorerAdapter(MetricScorer delegate) {
        this.delegate = delegate;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public List<EvalScore> score(EvalSample sample) {
        if (sample.error() != null) {
            return List.of();
        }
        return List.of(EvalScore.of(delegate.name(),
                delegate.score(sample.retrievedDocIds(), sample.expectedDocIds())));
    }
}
