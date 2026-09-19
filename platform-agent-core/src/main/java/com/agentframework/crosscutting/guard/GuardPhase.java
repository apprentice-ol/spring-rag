package com.agentframework.crosscutting.guard;

/**
 * 守卫挂载点：守卫在这些执行边界上被调用。
 *
 * <p>挂载点覆盖整条执行链：Agent → Workflow → Node → Prompt → LLM / Tool → Output。</p>
 */
public enum GuardPhase {
    /**
     * 守卫在 Agent 执行前被调用。
     */
    BEFORE_AGENT,
    /**
     * 守卫在 Agent 执行后被调用。
     */
    BEFORE_WORKFLOW,
    /**
     * 守卫在 Workflow 执行前被调用。
     */
    AFTER_WORKFLOW,
    /**
     * 守卫在 Node 执行前被调用。
     */
    BEFORE_NODE,
    /**
     * 守卫在 Node 执行后被调用。
     */
    AFTER_NODE,
    /**
     * 守卫在 Prompt 执行前被调用。
     */
    BEFORE_PROMPT,
    /**
     * 守卫在 Prompt 执行后被调用。
     */
    BEFORE_LLM,
    /**
     * 守卫在 LLM 执行后被调用。
     */
    AFTER_LLM,
    /**
     * 守卫在 Tool 执行前被调用。
     */
    BEFORE_TOOL,
    /**
     * 守卫在 Tool 执行后被调用。
     */
    AFTER_TOOL,
    /**
     * 守卫在 Output 执行前被调用。
     */
    BEFORE_OUTPUT,
    /**
     * 守卫在 Output 执行后被调用。
     */
    AFTER_OUTPUT,
    /**
     * 守卫在出错时被调用。
     */
    ON_ERROR
}
