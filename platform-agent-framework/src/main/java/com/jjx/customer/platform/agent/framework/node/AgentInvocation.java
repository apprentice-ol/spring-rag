package com.jjx.customer.platform.agent.framework.node;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次（子）Agent 执行的执行级上下文：递归深度、调用链（环检测）、共享账本、预算切分与截止。
 *
 * <p>顶层执行由引擎构造（{@link #root}）；{@code AGENT_CALL} 经 {@link AgentInvoker} 派生
 * 子调用（{@link #child}）——链追加目标、账本共享、预算取 {@code min(budgetShare, 父剩余)}、
 * 截止取更早者。</p>
 *
 * @param depth     递归深度（顶层 = 0）
 * @param agentChain 从主 Agent 到当前执行的调用链（环检测用；含当前层）
 * @param ledger    共享总账（null = 顶层自建）
 * @param llmBudget 本执行的 LLM 调用上限（-1 = 由 Workflow 自身声明决定）
 * @param deadline  截止时间（epoch millis；Long.MAX_VALUE = 不限）
 */
public record AgentInvocation(int depth, List<String> agentChain, ExecutionLedger ledger,
                              int llmBudget, long deadline) {

    public AgentInvocation {
        agentChain = agentChain == null || agentChain.isEmpty()
                ? List.of() : List.copyOf(agentChain);
    }

    /** 顶层执行的调用上下文。 */
    public static AgentInvocation root(String agentId) {
        return new AgentInvocation(0, List.of(agentId), null, -1, Long.MAX_VALUE);
    }

    /**
     * 派生子调用：深度 +1、链追加目标（调用方负责环检测）、账本共享父账、
     * 预算取 {@code min(budgetShare, 父剩余)}（budgetShare &le; 0 或父不限时取对方）、
     * 截止取更早者。
     *
     * @param targetAgentId   子 Agent id
     * @param budgetShare     父阶段声明的预算切分上限（0 = 不切分）
     * @param parentRemaining 父账剩余 LLM 次数（-1 = 不限）
     * @param parentDeadline  父执行截止时间
     */
    public AgentInvocation child(String targetAgentId, int budgetShare,
                                 int parentRemaining, long parentDeadline) {
        List<String> chain = new ArrayList<>(agentChain);
        chain.add(targetAgentId);
        int budget = llmBudget;
        if (budgetShare > 0) {
            budget = budget < 0 ? budgetShare : Math.min(budget, budgetShare);
        }
        if (parentRemaining >= 0) {
            budget = budget < 0 ? parentRemaining : Math.min(budget, parentRemaining);
        }
        return new AgentInvocation(depth + 1, chain, ledger, budget,
                Math.min(deadline, parentDeadline));
    }
}
