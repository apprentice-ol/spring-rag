package com.jjx.customer.platform.business;

import com.jjx.customer.platform.agent.framework.result.ExecutionResult;
import com.jjx.customer.platform.agent.framework.trace.AgentStep;
import com.jjx.customer.platform.business.trace.model.TraceView;

/**
 * 框架轨迹 → 业务轨迹映射（前端 SSE trace 事件与落库沿用既有结构）。
 *
 * <p>执行指纹三元组（agent/workflow/promptHash）随轨迹透传——SSE 事件、
 * sa_agent_trace、eval 的 trace jsonb 共用本结构（不变量 5）。</p>
 */
public final class FrameworkTraceMapper {

    private FrameworkTraceMapper() {
    }

    public static TraceView toBusinessTrace(ExecutionResult result) {
        String workflowId = result.fingerprint() == null ? null : result.fingerprint().workflowId();
        String promptHash = result.fingerprint() == null ? null : result.fingerprint().promptHash();
        TraceView trace = new TraceView(result.fingerprint().agentId(), workflowId, promptHash);
        for (AgentStep step : result.trace().steps()) {
            trace.step(step.action(), step.thought(), null, step.output(), step.startedAt());
        }
        for (int i = 0; i < result.trace().llmCalls(); i++) {
            trace.incrementLlmCall();
        }
        return trace;
    }
}
