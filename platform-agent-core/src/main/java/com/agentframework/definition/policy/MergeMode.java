package com.agentframework.definition.policy;

/**
 * 策略合并模式：决定一个作用域的声明如何与继承而来的集合合并。
 *
 * <p>作用域链由外到内为 {@code GLOBAL → AGENT → WORKFLOW → REGION → NODE}。</p>
 */
public enum MergeMode {

    /** 与继承集合求并集，是默认且最安全的模式。 */
    ADD,

    /** 丢弃继承的非强制集合，仅保留本作用域声明的集合（强制组件不受影响）。 */
    REPLACE
}
