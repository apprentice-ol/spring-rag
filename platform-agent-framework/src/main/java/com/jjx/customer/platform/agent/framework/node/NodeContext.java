package com.jjx.customer.platform.agent.framework.node;

import com.jjx.customer.platform.agent.framework.agent.AgentRequest;
import com.jjx.customer.platform.agent.framework.plan.ExecutionPlan;
import com.jjx.customer.platform.agent.framework.result.ExecutionMetadataSink;
import com.jjx.customer.platform.agent.framework.workflow.WorkflowStageSpec;

import java.util.Map;

/**
 * 一次节点执行的上下文：执行计划（agent/workflow/能力/指纹）+ 当前阶段 + 槽位 + 执行级调用信息。
 *
 * <p>由引擎构造并注入，节点执行器与工具都从这里取运行态（不用 ThreadLocal）。</p>
 *
 * @param systemAppend replan adjust 重跑时注入的修正要求（拼在阶段 system 之后；null = 正常执行）
 */
public record NodeContext(ExecutionPlan plan,
                          WorkflowStageSpec stage,
                          AgentRequest request,
                          Map<String, Object> slots,
                          AgentInvocation invocation,
                          AgentInvoker agentInvoker,
                          ExecutionMetadataSink metadataSink,
                          BudgetView budget,
                          String systemAppend) {

    public NodeContext {
        slots = slots == null ? Map.of() : Map.copyOf(slots);
        invocation = invocation == null ? AgentInvocation.root(plan.agent().id()) : invocation;
        budget = budget == null ? BudgetView.unlimited() : budget;
    }

    public String prompt(String key) {
        return plan.promptSnapshot().prompt(key);
    }

    public String promptOrNull(String key) {
        return plan.promptSnapshot().promptOrNull(key);
    }

    /** 当前递归深度（顶层 = 0）。 */
    public int depth() {
        return invocation.depth();
    }
}
