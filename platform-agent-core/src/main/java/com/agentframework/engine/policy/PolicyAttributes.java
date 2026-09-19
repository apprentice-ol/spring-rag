package com.agentframework.engine.policy;

/** 策略相关上下文属性键。 */
public final class PolicyAttributes {

    /** 当前节点已解析的策略，值类型为 {@link ResolvedPolicy}。 */
    public static final String RESOLVED = "policy.resolved";

    /** 当前运行的策略索引，值类型为 {@link ResolvedPolicyIndex}。 */
    public static final String INDEX = "policy.index";

    private PolicyAttributes() {
    }
}
