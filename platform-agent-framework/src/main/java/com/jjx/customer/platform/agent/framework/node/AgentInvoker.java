package com.jjx.customer.platform.agent.framework.node;

import com.jjx.customer.platform.agent.framework.agent.AgentRequest;
import com.jjx.customer.platform.agent.framework.result.ExecutionMetadataSink;
import com.jjx.customer.platform.agent.framework.result.ExecutionResult;

/**
 * 子 Agent 调用入口（由引擎实现——重入同一执行内核，预算总账与 trace 树共享）。
 *
 * <p>{@code AGENT_CALL} 节点执行器只依赖本接口，不直接依赖引擎实现，避免环依赖。
 * 引擎实现负责：环检测（调用链含目标即拒绝）、深度上限校验、子调用上下文派生
 * （链追加 / 账本共享 / 预算切分 {@code min(budgetShare, 父剩余)} / 截止收紧）与重入执行。</p>
 */
@FunctionalInterface
public interface AgentInvoker {

    /**
     * @param agentId     目标子 Agent id
     * @param request     子请求（由父节点按 inputMapping 组装）
     * @param sink        元数据出口（子级产出向父上卷）
     * @param parent      父执行上下文（深度/调用链/账本）
     * @param parentBudget 父阶段当前的预算视图（剩余次数与截止；预算切分的父侧约束）
     * @param budgetShare 父阶段声明的预算切分上限（0 = 不切分，子受自身 Workflow 限额约束）
     */
    ExecutionResult invoke(String agentId, AgentRequest request, ExecutionMetadataSink sink,
                           AgentInvocation parent, BudgetView parentBudget, int budgetShare);
}
