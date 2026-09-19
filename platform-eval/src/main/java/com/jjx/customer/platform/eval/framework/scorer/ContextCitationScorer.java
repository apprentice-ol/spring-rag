package com.jjx.customer.platform.eval.framework.scorer;
import com.jjx.customer.platform.business.knowledge.rag.RagContextAssembler;

import com.jjx.customer.platform.business.knowledge.rag.RagContextAssembler;
import com.jjx.customer.platform.eval.framework.EvalSample;
import com.jjx.customer.platform.eval.framework.EvalScore;
import com.jjx.customer.platform.eval.framework.EvalScorer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 上下文级比对 scorer（RAGAS context_recall / context_precision 的 Java 版）：
 * 拿「最后推送 LLM 的数据」——线上同款 {@link RagContextAssembler} 组装产出的 citations
 * （实际进入回答上下文的文档集合）——与标准召回（expected_doc_ids）比对。
 * <p>与 recall_at_k 的区别：recall_at_k 吃检索引擎返回的文档序列（截 k），
 * 本指标吃上下文里真实呈现的文档集合（全量），两者在 contextTopK 裁剪 / 分组合并后可能不同——
 * 差值即"检索到了但没进上下文"的损耗。集合数学直算（recall 空期望=1.0、precision 空上下文=0，
 * 与既有 MetricScorer 约定一致）。
 * <p>comment 产出文件名对照表（期望 ✓/✗ + 实际清单），随 Langfuse score 展示——
 * 即「标准召回 / 文件名称」的直观比对视图。
 */
@Component
public class ContextCitationScorer implements EvalScorer {

    @Override
    public String name() {
        return "context_citation";
    }

    @Override
    public List<EvalScore> score(EvalSample sample) {
        if (sample.error() != null || sample.context() == null) {
            return List.of();
        }
        // citations 的 docId 序列 = 上下文里真实呈现的文档（null docId 的匿名块跳过）
        LinkedHashSet<String> contextDocIds = new LinkedHashSet<>();
        for (RagContextAssembler.Citation c : sample.context().citations()) {
            if (c.docId() != null) {
                contextDocIds.add(c.docId());
            }
        }
        List<String> expected = sample.expectedDocIds();
        Set<String> expectedSet = Set.copyOf(expected);
        long hit = contextDocIds.stream().filter(expectedSet::contains).count();
        double recall = expected.isEmpty() ? 1.0 : (double) hit / expected.size();
        double precision = contextDocIds.isEmpty() ? 0.0 : (double) hit / contextDocIds.size();

        String comment = buildComment(sample);
        return List.of(
                new EvalScore("context_recall", recall, comment),
                new EvalScore("context_precision", precision, comment));
    }

    /** 文件名对照表：期望文档逐个标 ✓/✗（是否被检索召回）+ 实际召回清单。 */
    private String buildComment(EvalSample sample) {
        Set<String> retrievedNames = new LinkedHashSet<>(sample.retrievedDocNames());
        StringBuilder sb = new StringBuilder("期望: ");
        for (String name : sample.expectedDocNames()) {
            sb.append(retrievedNames.contains(name) ? name + "✓" : name + "✗").append(' ');
        }
        sb.append("| 实际: ").append(String.join(", ", sample.retrievedDocNames()));
        return sb.toString();
    }
}
