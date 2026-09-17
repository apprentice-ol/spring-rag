package com.jjx.customer.platform.agent.framework.trace;

import com.jjx.customer.platform.agent.framework.node.ExecutionLedger;
import com.jjx.customer.platform.agent.framework.result.ExecutionFingerprint;
import com.jjx.customer.platform.agent.framework.trace.AgentStep;
import com.jjx.customer.platform.agent.framework.trace.AgentTrace;

import java.util.ArrayList;
import java.util.List;

/**
 * 轨迹累加器（引擎固定切点写入；对外的 {@link AgentTrace} 是不可变快照）。
 *
 * <p>实现 {@link ExecutionLedger}：子 Agent 重入时经父账本构造（{@link #ExecutionTrace(ExecutionLedger)}），
 * 自身计数同时上卷父账——预算总账全树只有一份（设计 §4/D6）。
 * steps 每执行独立；子执行轨迹作为嵌套步骤挂到父的 AGENT_CALL 步骤下。</p>
 */
public class ExecutionTrace implements ExecutionLedger {

    private final List<AgentStep> steps = new ArrayList<>();
    private final ExecutionLedger parent;
    private int llmCalls;
    private int toolCalls;

    /** 顶层账本。 */
    public ExecutionTrace() {
        this(null);
    }

    /** 子执行账本：自身计数上卷 {@code parent}（父账本由调用链逐层共享）。 */
    public ExecutionTrace(ExecutionLedger parent) {
        this.parent = parent;
    }

    public void step(String action, String thought, String output, String status, long startedAt) {
        steps.add(new AgentStep(action, thought, output, status, startedAt,
                Math.max(0, System.currentTimeMillis() - startedAt)));
    }

    /** 带嵌套子步骤的步骤（子 Agent 重入的轨迹上卷）。 */
    public void step(String action, String thought, String output, String status, long startedAt,
                     List<AgentStep> children) {
        steps.add(new AgentStep(action, thought, output, status, startedAt,
                Math.max(0, System.currentTimeMillis() - startedAt), children));
    }

    public void addLlmCalls(int count) {
        llmCalls += Math.max(0, count);
        if (parent != null) {
            parent.consumeLlm(count);
        }
    }

    public void addToolCalls(int count) {
        toolCalls += Math.max(0, count);
        if (parent != null) {
            parent.consumeTool(count);
        }
    }

    @Override
    public void consumeLlm(int count) {
        addLlmCalls(count);
    }

    @Override
    public void consumeTool(int count) {
        addToolCalls(count);
    }

    @Override
    public int llmCalls() {
        return llmCalls;
    }

    @Override
    public int toolCalls() {
        return toolCalls;
    }

    public AgentTrace toTrace(String agentId, String workflowId, ExecutionFingerprint fingerprint) {
        return new AgentTrace(agentId, workflowId, List.copyOf(steps), llmCalls, toolCalls, fingerprint);
    }
}
