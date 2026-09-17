package com.jjx.customer.platform.agent.framework.agent;

import com.jjx.customer.platform.agent.framework.node.AgentInvocation;
import com.jjx.customer.platform.agent.framework.node.AgentInvoker;
import com.jjx.customer.platform.agent.framework.node.BudgetView;
import com.jjx.customer.platform.agent.framework.result.ExecutionMetadataSink;
import com.jjx.customer.platform.agent.framework.result.ExecutionResult;

/**
 * {@link AgentInvoker} 的引擎适配实现（Adapter 模式）。
 *
 * <p>命名规约：<b>接口 = 角色，实现 = 组件 + 角色</b>。{@code AGENT_CALL} 节点只依赖
 * "子 Agent 调用"这个角色（{@link AgentInvoker}），本类名直接读出"由 {@link WorkflowEngine}
 * 承担该角色"，不再让引擎隐式兼任、让调用方看不出实现关系。</p>
 *
 * <p>调用策略在适配层收口（设计 §4）：环检测（调用链含目标即拒绝）、深度上限校验、子调用上下文派生
 * （深度 +1 / 链追加目标 / 账本共享 / 预算切分 {@code min(budgetShare, 父剩余)} / 截止收紧）；
 * 执行本身仍复用 {@link WorkflowEngine} 的同一执行内核。</p>
 */
public final class WorkflowEngineAgentInvoker implements AgentInvoker {

    private final WorkflowEngine engine;
    private final AgentRegistry agentRegistry;
    private final int maxDepth;

    public WorkflowEngineAgentInvoker(WorkflowEngine engine, AgentRegistry agentRegistry, int maxDepth) {
        this.engine = engine;
        this.agentRegistry = agentRegistry;
        this.maxDepth = Math.max(1, maxDepth);
    }

    @Override
    public ExecutionResult invoke(String agentId, AgentRequest request,
                                  ExecutionMetadataSink sink, AgentInvocation parent,
                                  BudgetView parentBudget, int budgetShare) {
        if (parent.agentChain().contains(agentId)) {
            throw new IllegalStateException("子 Agent 调用成环: " + agentId
                    + "（链=" + parent.agentChain() + "）");
        }
        if (parent.depth() + 1 > maxDepth) {
            throw new IllegalStateException("子 Agent 调用深度超限: " + (parent.depth() + 1) + " > " + maxDepth
                    + "（agent=" + agentId + "）");
        }
        Agent agent = agentRegistry.byId(agentId)
                .orElseThrow(() -> new IllegalStateException("子 Agent 未注册: " + agentId));
        AgentInvocation child = parent.child(agentId, budgetShare,
                parentBudget == null ? -1 : parentBudget.remainingLlmCalls(),
                parentBudget == null ? Long.MAX_VALUE : parentBudget.deadlineMillis());
        return engine.reenter(agent, request, sink, child);
    }
}
