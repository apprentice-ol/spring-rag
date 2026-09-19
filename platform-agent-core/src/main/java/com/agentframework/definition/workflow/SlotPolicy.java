package com.agentframework.definition.workflow;

/**
 * 槽位契约的严格度策略，决定 {@code EXPRESSION_UNKNOWN_SLOT} 是阻断性错误还是警告。
 *
 * <p>引擎自动识别的槽位（节点 outputSlot、派生槽 {@code *_tool_calls} / {@code *_data} /
 * {@code *_slots}、Region 计数槽）始终视为已知槽位，不受策略影响；
 * 策略只作用于这两者与显式 {@code .slot(...)} 声明之外的引用。</p>
 *
 * <p>默认（未显式指定时）沿用既有语义：声明过任意 {@code .slot(...)} 即 STRICT，
 * 完全不声明则 OPEN——可经 {@link WorkflowBuilder#strictSlots()} / {@link WorkflowBuilder#openSlots()}
 * 显式覆盖。</p>
 */
public enum SlotPolicy {

    /** 严格：条件表达式引用未知槽位为 ERROR，构建阻断。 */
    STRICT,

    /** 开放：未知槽位引用仅产生 WARNING（附已知槽位候选）。 */
    OPEN
}
