package com.jjx.customer.platform.agent.framework.node;

/**
 * 执行计数总账（引擎内实现；子 Agent 重入时父子共享同一账本，子消耗自动上卷父账）。
 *
 * <p>设计 §4/D6：预算总账只有一份——父可给上限（budgetShare），子不可绕过；
 * 引擎的预算检查（{@code DefaultWorkflowDriver}）读同一账本。</p>
 */
public interface ExecutionLedger {

    int llmCalls();

    int toolCalls();

    void consumeLlm(int count);

    void consumeTool(int count);
}
