package com.jjx.customer.platform.eval.domain;

/**
 * bad case 归因环节（{@code sa_eval_item.triage.stage}）：失败环节分类，规则预判 + 人工确认。
 *
 * <p>对应线上查询流水线的各环节，便于按环节聚合做针对性优化（见
 * docs/plans/2026-10-11-badcase-collection.md 归因规则表）。</p>
 */
public enum TriageStage {
    /** 证据不足（无轨迹 / 轨迹解析失败），需人工判断 */
    UNKNOWN,
    /** 意图误判：判定无需检索或域判非知识问答，压根没走检索 */
    INTENT_MISROUTE,
    /** 检索未命中：各通道均无召回 */
    NO_RECALL,
    /** 后处理砍光：通道有候选但融合/精排后 0 条达标（rerank 阈值类问题） */
    FILTERED_OUT,
    /** 排序靠后：期望文档已召回但排名靠后（评测有 ground truth 场景） */
    RANKING,
    /** 生成问题：上下文已进提示词但答案不行（幻觉/不全/答非所问） */
    GENERATION
}
