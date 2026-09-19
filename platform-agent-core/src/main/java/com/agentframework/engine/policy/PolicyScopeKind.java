package com.agentframework.engine.policy;

/**
 * 策略作用域：由外到内依次为 {@code GLOBAL → AGENT → WORKFLOW → REGION → NODE}。
 *
 * <p>{@link #REGION} 为后续范式治理预留，本期不产生实例。</p>
 */
public enum PolicyScopeKind {
    GLOBAL,
    AGENT,
    WORKFLOW,
    REGION,
    NODE
}
