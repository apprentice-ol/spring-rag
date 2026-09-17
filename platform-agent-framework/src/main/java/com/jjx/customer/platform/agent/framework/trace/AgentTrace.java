package com.jjx.customer.platform.agent.framework.trace;

import com.jjx.customer.platform.agent.framework.result.ExecutionFingerprint;

import java.util.List;

/**
 * 执行轨迹（引擎固定切点自动记录）。
 *
 * @param agentId     Agent 标识
 * @param workflowId  Workflow 标识
 * @param steps       轨迹步骤（顺序；子 Agent 重入的轨迹嵌套在父步骤的 children 内）
 * @param llmCalls    LLM 调用次数（本执行自身消耗；子消耗经共享账本上卷进父）
 * @param toolCalls   工具调用次数
 * @param fingerprint 执行指纹（agent + workflow + promptHash；不变量 5——进 trace/缓存/eval。可空＝早期构造）
 */
public record AgentTrace(String agentId,
                         String workflowId,
                         List<AgentStep> steps,
                         int llmCalls,
                         int toolCalls,
                         ExecutionFingerprint fingerprint) {

    public AgentTrace {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public AgentTrace(String agentId, String workflowId, List<AgentStep> steps,
                      int llmCalls, int toolCalls) {
        this(agentId, workflowId, steps, llmCalls, toolCalls, null);
    }

    public static AgentTrace empty(String agentId, String workflowId) {
        return new AgentTrace(agentId, workflowId, List.of(), 0, 0);
    }
}
