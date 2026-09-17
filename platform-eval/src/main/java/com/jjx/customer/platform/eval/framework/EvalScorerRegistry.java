package com.jjx.customer.platform.eval.framework;

import com.jjx.customer.platform.eval.config.EvalProperties;
import com.jjx.customer.platform.eval.framework.scorer.DocSetScorerAdapter;
import com.jjx.customer.platform.eval.metrics.MrrScorer;
import com.jjx.customer.platform.eval.metrics.NdcgScorer;
import com.jjx.customer.platform.eval.metrics.PrecisionAtKScorer;
import com.jjx.customer.platform.eval.metrics.RecallAtKScorer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 评测维度注册表：doc 集指标按 {@code rag.eval.ks} 配置组装适配器（k 参数化，非 bean），
 * 其余维度收集所有 {@link EvalScorer} bean（@Component 即注册——加维度零改本类，开闭）。
 */
@Component
public class EvalScorerRegistry {

    private final List<EvalScorer> scorers;

    public EvalScorerRegistry(List<EvalScorer> componentScorers, EvalProperties evalProperties) {
        List<EvalScorer> all = new ArrayList<>();
        // doc 集指标（mrr + 按 k 的 recall/precision/ndcg）——数学与口径与改造前完全一致
        all.add(new DocSetScorerAdapter(new MrrScorer()));
        for (int k : evalProperties.getKs()) {
            all.add(new DocSetScorerAdapter(new RecallAtKScorer(k)));
            all.add(new DocSetScorerAdapter(new PrecisionAtKScorer(k)));
            all.add(new DocSetScorerAdapter(new NdcgScorer(k)));
        }
        all.addAll(componentScorers);
        this.scorers = List.copyOf(all);
    }

    public List<EvalScorer> scorers() {
        return scorers;
    }
}
