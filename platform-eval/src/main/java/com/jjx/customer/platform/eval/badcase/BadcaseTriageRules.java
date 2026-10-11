package com.jjx.customer.platform.eval.badcase;

import com.jjx.customer.platform.eval.domain.BadcaseEvidence;
import com.jjx.customer.platform.eval.domain.TriageStage;
import com.jjx.customer.platform.intent.IntentResult;

/**
 * bad case 归因规则：从证据快照推导疑似失败环节（{@link TriageStage}），人工确认兜底。
 *
 * <p>纯函数、无依赖（证据取数在 {@link EvidenceExtractor}），规则序即优先级——
 * 越靠前的环节越"上游"，先错先归因（意图没走检索时，讨论召回率没有意义）。</p>
 */
public final class BadcaseTriageRules {

    private BadcaseTriageRules() {
    }

    /** 规则判定结果（环节 + 依据说明，说明进 triage.note 供人工确认时参考）。 */
    public record Verdict(TriageStage stage, String note) {
    }

    /**
     * 判定失败环节。
     *
     * <p>规则序（详见 docs/plans/2026-10-11-badcase-collection.md）：
     * 无轨迹 → UNKNOWN；意图误判 → INTENT_MISROUTE；无候选 → NO_RECALL；
     * 有候选全被砍 → FILTERED_OUT；有上下文进提示词 → GENERATION。
     * RANKING（期望文档召回但靠后）需要评测 ground truth，由 run 归集路径另行判定。</p>
     */
    public static Verdict judge(BadcaseEvidence evidence) {
        if (evidence == null) {
            return new Verdict(TriageStage.UNKNOWN, "无证据快照");
        }
        if (!evidence.hasTrace()) {
            return new Verdict(TriageStage.UNKNOWN, "无 agent 轨迹（闲聊 / 缓存重放 / 降级路径），需人工判断");
        }
        BadcaseEvidence.IntentEvidence intent = evidence.intent();
        if (intent == null) {
            return new Verdict(TriageStage.UNKNOWN, "轨迹里无意图分类产物（旧数据或缺步），需人工判断");
        }
        if (Boolean.FALSE.equals(intent.needsRetrieval())) {
            return new Verdict(TriageStage.INTENT_MISROUTE,
                    "意图判定无需检索（needs_retrieval=false），压根没走检索");
        }
        if (intent.domain() != null && !IntentResult.DOMAIN_KNOWLEDGE.equals(intent.domain())) {
            return new Verdict(TriageStage.INTENT_MISROUTE,
                    "意图域判定为 " + intent.domain() + "（非知识问答），走错分支");
        }
        BadcaseEvidence.RetrievalEvidence retrieval = evidence.retrieval();
        boolean hasCitations = evidence.citations() != null && !evidence.citations().isEmpty();
        if (retrieval == null) {
            return hasCitations
                    ? new Verdict(TriageStage.GENERATION, "有引用上下文但无检索明细，疑似生成环节")
                    : new Verdict(TriageStage.UNKNOWN, "无检索明细且无引用，证据不足以自动判定");
        }
        Integer finalHits = retrieval.finalHits();
        Integer candidates = retrieval.channelCandidates();
        if (finalHits != null && finalHits > 0) {
            return new Verdict(TriageStage.GENERATION,
                    "已检索到 " + finalHits + " 条上下文进提示词，但答案不满意——疑似生成环节");
        }
        if (candidates != null && candidates > 0) {
            return new Verdict(TriageStage.FILTERED_OUT,
                    "各通道召回 " + candidates + " 条候选，融合/精排后 0 条达标——相关度阈值类问题");
        }
        if (candidates != null) {
            return new Verdict(TriageStage.NO_RECALL, "各通道均无召回——语料缺失或表述差异过大");
        }
        // 无通道明细（react 线 / 旧轨迹）时用命中片段数与引用数兜底
        boolean hasChunks = retrieval.chunks() != null && !retrieval.chunks().isEmpty();
        if (hasChunks || hasCitations) {
            return new Verdict(TriageStage.GENERATION, "有检索命中/引用但答案不满意，疑似生成环节");
        }
        return new Verdict(TriageStage.NO_RECALL, "无检索命中且无引用，疑似未召回");
    }
}
