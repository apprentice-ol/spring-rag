package com.jjx.customer.platform.agent.framework.node;

import com.jjx.customer.platform.agent.framework.result.ContextArtifact;
import com.jjx.customer.platform.agent.framework.trace.AgentStep;

import java.util.List;
import java.util.Map;

/**
 * 节点执行结果：产出文本 + 证据片段 + 计数 + 状态（出环原因/失败原因）。
 *
 * @param nestedSteps 嵌套子步骤（AGENT_CALL 场景 = 子执行轨迹，挂到父 trace 的本阶段步骤下）
 * @param slotUpdates 回写父槽位（AGENT_CALL 的 outputMapping 产出；影响后续阶段的 when 条件）
 */
public record NodeResult(String text, String status, int llmCalls, int toolCalls,
                         List<ContextArtifact> artifacts,
                         List<AgentStep> nestedSteps,
                         Map<String, Object> slotUpdates) {

    public NodeResult {
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        nestedSteps = nestedSteps == null ? List.of() : List.copyOf(nestedSteps);
        slotUpdates = slotUpdates == null ? Map.of() : Map.copyOf(slotUpdates);
    }

    public NodeResult(String text, String status, int llmCalls, int toolCalls,
                      List<ContextArtifact> artifacts) {
        this(text, status, llmCalls, toolCalls, artifacts, List.of(), Map.of());
    }

    public static NodeResult ok(String text) {
        return new NodeResult(text, "OK", 0, 0, List.of());
    }

    public static NodeResult ok(String text, int llmCalls, int toolCalls, List<ContextArtifact> artifacts) {
        return new NodeResult(text, "OK", llmCalls, toolCalls, artifacts);
    }

    /** 带嵌套子轨迹与槽位回写的 OK 结果（AGENT_CALL 用）。 */
    public static NodeResult ok(String text, int llmCalls, int toolCalls, List<ContextArtifact> artifacts,
                                List<AgentStep> nestedSteps, Map<String, Object> slotUpdates) {
        return new NodeResult(text, "OK", llmCalls, toolCalls, artifacts, nestedSteps, slotUpdates);
    }

    public static NodeResult skipped() {
        return new NodeResult(null, "SKIPPED", 0, 0, List.of());
    }

    public static NodeResult failed(String reason) {
        return new NodeResult(null, "FAILED:" + reason, 0, 0, List.of());
    }
}
