package com.jjx.customer.platform.eval.badcase;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jjx.customer.platform.eval.domain.BadcaseEvidence;
import com.jjx.customer.platform.eval.domain.TriageStage;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * bad case 归因规则测试：锁死证据 → 环节的判定序（见 docs/plans/2026-10-11-badcase-collection.md 规则表）。
 *
 * <p>规则序即优先级：越上游的环节先判——意图没走检索时讨论召回率没有意义。</p>
 */
class BadcaseTriageRulesTest {

    private static BadcaseEvidence evidence(boolean hasTrace,
                                            BadcaseEvidence.IntentEvidence intent,
                                            BadcaseEvidence.RetrievalEvidence retrieval,
                                            List<BadcaseEvidence.CitationBrief> citations) {
        return new BadcaseEvidence("问题", "conv-1", "答案", "trace-1", "knowledge",
                hasTrace, intent, retrieval, citations, List.of(), null);
    }

    /** 正常知识问答意图（needs_retrieval=true、域=knowledge）。 */
    private static BadcaseEvidence.IntentEvidence knowledgeIntent() {
        return new BadcaseEvidence.IntentEvidence("knowledge", 0.9, true, false, "问操作方法", "retrieve");
    }

    private static BadcaseEvidence.RetrievalEvidence retrieval(Integer candidates, Integer finalHits) {
        return new BadcaseEvidence.RetrievalEvidence(candidates, finalHits, List.of(), List.of());
    }

    @Test
    void noTrace_unknown() {
        TriageStage stage = BadcaseTriageRules.judge(
                evidence(false, null, null, List.of())).stage();
        assertEquals(TriageStage.UNKNOWN, stage);
    }

    @Test
    void noIntentInSteps_unknown() {
        TriageStage stage = BadcaseTriageRules.judge(
                evidence(true, null, null, List.of())).stage();
        assertEquals(TriageStage.UNKNOWN, stage);
    }

    @Test
    void needsRetrievalFalse_intentMisroute() {
        BadcaseEvidence.IntentEvidence intent = new BadcaseEvidence.IntentEvidence(
                "knowledge", 0.95, false, false, "问候语，无需检索", "chat");
        TriageStage stage = BadcaseTriageRules.judge(
                evidence(true, intent, retrieval(0, 0), List.of())).stage();
        assertEquals(TriageStage.INTENT_MISROUTE, stage);
    }

    @Test
    void nonKnowledgeDomain_intentMisroute() {
        BadcaseEvidence.IntentEvidence intent = new BadcaseEvidence.IntentEvidence(
                "ops", 0.8, true, false, "疑似故障排查", "ops_diagnose");
        TriageStage stage = BadcaseTriageRules.judge(
                evidence(true, intent, retrieval(0, 0), List.of())).stage();
        assertEquals(TriageStage.INTENT_MISROUTE, stage);
    }

    @Test
    void noChannelCandidates_noRecall() {
        TriageStage stage = BadcaseTriageRules.judge(
                evidence(true, knowledgeIntent(), retrieval(0, 0), List.of())).stage();
        assertEquals(TriageStage.NO_RECALL, stage);
    }

    @Test
    void candidatesButZeroFinalHits_filteredOut() {
        TriageStage stage = BadcaseTriageRules.judge(
                evidence(true, knowledgeIntent(), retrieval(20, 0), List.of())).stage();
        assertEquals(TriageStage.FILTERED_OUT, stage);
    }

    @Test
    void finalHitsPositive_generation() {
        TriageStage stage = BadcaseTriageRules.judge(
                evidence(true, knowledgeIntent(), retrieval(20, 5), List.of())).stage();
        assertEquals(TriageStage.GENERATION, stage);
    }

    @Test
    void noRetrievalDetailWithCitations_generation() {
        TriageStage stage = BadcaseTriageRules.judge(
                evidence(true, knowledgeIntent(), null,
                        List.of(new BadcaseEvidence.CitationBrief("doc-1", "手册.md", 3)))).stage();
        assertEquals(TriageStage.GENERATION, stage);
    }

    @Test
    void noRetrievalDetailWithoutCitations_unknown() {
        // react 等轴无 retrieval 明细且无引用：证据不足，不武断归 NO_RECALL
        TriageStage stage = BadcaseTriageRules.judge(
                evidence(true, knowledgeIntent(), null, List.of())).stage();
        assertEquals(TriageStage.UNKNOWN, stage);
    }

    @Test
    void nullEvidence_unknown() {
        assertEquals(TriageStage.UNKNOWN, BadcaseTriageRules.judge(null).stage());
    }

    @Test
    void reactAxis_chunksWithoutChannelDetail_generation() {
        // react 线：无通道明细（retrieval.channels/finalHits 缺），但有命中片段 → 生成环节
        BadcaseEvidence.RetrievalEvidence react = new BadcaseEvidence.RetrievalEvidence(
                null, null, null,
                List.of(new BadcaseEvidence.ChunkBrief(1, "doc-1", "手册.md", 0.61, null, "KEYWORD", "预览")));
        TriageStage stage = BadcaseTriageRules.judge(
                evidence(true, knowledgeIntent(), react, List.of())).stage();
        assertEquals(TriageStage.GENERATION, stage);
    }

    @Test
    void reactAxis_noChunksNoCitations_noRecall() {
        BadcaseEvidence.RetrievalEvidence react = new BadcaseEvidence.RetrievalEvidence(
                null, null, null, List.of());
        TriageStage stage = BadcaseTriageRules.judge(
                evidence(true, knowledgeIntent(), react, List.of())).stage();
        assertEquals(TriageStage.NO_RECALL, stage);
    }
}
