package com.jjx.customer.platform.eval.framework.scorer;
import com.jjx.customer.platform.business.knowledge.rag.RagContextAssembler;

import com.jjx.customer.platform.business.knowledge.rag.RagContextAssembler;
import com.jjx.customer.platform.eval.framework.EvalSample;
import com.jjx.customer.platform.eval.framework.EvalScore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文级比对 scorer 单测：citations（实际进 LLM 的文档）vs 标准召回的
 * recall/precision 数学 + 文件名对照 comment 格式 + 各跳过态。
 */
class ContextCitationScorerTest {

    private final ContextCitationScorer scorer = new ContextCitationScorer();

    /** 构造带 citations 的上下文（citations 的 docId 序列即"上下文里真实呈现的文档"）。 */
    private static RagContextAssembler.RagContext context(String... docIds) {
        List<RagContextAssembler.Citation> citations = new java.util.ArrayList<>();
        int ref = 1;
        for (String docId : docIds) {
            citations.add(new RagContextAssembler.Citation(ref++, docId,
                    docId + ".md", 1, "preview", null));
        }
        return new RagContextAssembler.RagContext("<documents></documents>", docIds.length, citations);
    }

    private static EvalSample sample(RagContextAssembler.RagContext context,
                                     List<String> expectedIds, List<String> expectedNames,
                                     List<String> retrievedNames) {
        return EvalSample.builder()
                .itemId(1L)
                .question("发票冲红")
                .expectedDocIds(expectedIds)
                .expectedDocNames(expectedNames)
                .retrievedDocIds(expectedIds)
                .retrievedDocNames(retrievedNames)
                .context(context)
                .build();
    }

    @Test
    void 全命中_recall与precision为1() {
        EvalSample s = sample(context("d1", "d2"), List.of("d1", "d2"),
                List.of("发票.md", "交接.md"), List.of("发票.md", "交接.md"));
        List<EvalScore> scores = scorer.score(s);
        assertEquals(2, scores.size());
        assertEquals(1.0, scores.get(0).value(), 1e-9, "context_recall");
        assertEquals(1.0, scores.get(1).value(), 1e-9, "context_precision");
    }

    @Test
    void 部分命中() {
        // 上下文进了 3 个文档（d1/d2/d3），期望 d1/d4 → recall=1/2、precision=1/3
        EvalSample s = sample(context("d1", "d2", "d3"), List.of("d1", "d4"),
                List.of("发票.md", "报销.md"), List.of("发票.md", "交接.md", "杂项.md"));
        List<EvalScore> scores = scorer.score(s);
        assertEquals(0.5, scores.get(0).value(), 1e-9, "context_recall");
        assertEquals(1.0 / 3, scores.get(1).value(), 1e-9, "context_precision");
    }

    @Test
    void 空期望_recall为1空上下文_precision为0() {
        EvalSample emptyExpected = sample(context("d1"), List.of(), List.of(), List.of("发票.md"));
        assertEquals(1.0, scorer.score(emptyExpected).get(0).value(), 1e-9);

        EvalSample emptyCtx = sample(context(), List.of("d1"), List.of("发票.md"), List.of());
        assertEquals(0.0, scorer.score(emptyCtx).get(1).value(), 1e-9);
    }

    @Test
    void comment是文件名对照表() {
        EvalSample s = sample(context("d1"), List.of("d1", "d2"),
                List.of("发票.md", "报销.md"), List.of("发票.md"));
        String comment = scorer.score(s).get(0).comment();
        assertTrue(comment.contains("发票.md✓"), "命中的期望文件名带 ✓: " + comment);
        assertTrue(comment.contains("报销.md✗"), "未命中的期望文件名带 ✗: " + comment);
        assertTrue(comment.contains("实际: 发票.md"), "含实际清单: " + comment);
    }

    @Test
    void 错误样本或无上下文跳过() {
        assertEmpty(scorer.score(EvalSample.builder().itemId(1L).error("boom").build()));
        assertEmpty(scorer.score(EvalSample.builder().itemId(1L).build()));
    }

    private static void assertEmpty(List<EvalScore> scores) {
        assertEquals(0, scores.size());
    }
}
