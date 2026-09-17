package com.jjx.customer.platform.agent.framework.result;

/**
 * 执行结果类型（统一形状，消费方按 kind 分派）。
 */
public enum OutcomeKind {

    /** 产出上下文（证据片段），由管线生成最终答复（配合 STREAMING 能力位）。 */
    WITH_CONTEXT,

    /** 直出文本（流程末节点终稿即最终答复）。 */
    DIRECT,

    /** 需要用户补充信息（缺必填槽位）。 */
    CLARIFY,

    /** 升级/终止（预算耗尽、无法推进等）。 */
    ESCALATE
}
