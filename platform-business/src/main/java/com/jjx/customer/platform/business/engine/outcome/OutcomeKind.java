package com.jjx.customer.platform.business.engine.outcome;

/**
 * 引擎出口语义（业务侧自有枚举，值名与旧内核 {@code OutcomeKind} 完全一致）。
 *
 * <p>SSE 事件协议、{@code DiagnoseResponse.kind}（{@code answer.kind().name()}）、
 * eval 断言均按枚举名字符串消费——迁移新内核后由 {@link RunOutcomeMapper} 从
 * {@code RunResult} 推导，本枚举是全链路契约的锚点，禁止改名/删值。</p>
 */
public enum OutcomeKind {

    /** 检索型带上下文出答案（knowledge 线：证据先就绪，生成交付段在引擎外流式）。 */
    WITH_CONTEXT,

    /** 直答（ops 结论 / 无检索上下文）。 */
    DIRECT,

    /** 澄清追问（引擎挂起等用户补槽位）。 */
    CLARIFY,

    /** 升级人工（预算触顶 / 无法收敛 / 置信不足）。 */
    ESCALATE
}
