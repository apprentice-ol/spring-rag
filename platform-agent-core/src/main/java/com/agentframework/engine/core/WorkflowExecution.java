package com.agentframework.engine.core;

import com.agentframework.crosscutting.trace.Span;
import com.agentframework.crosscutting.trace.TraceContext;
import com.agentframework.definition.workflow.WorkflowDefinition;
import com.agentframework.engine.agentmanager.Agent;
import com.agentframework.runtime.session.Input;
import com.agentframework.runtime.session.Session;
import com.agentframework.runtime.slot.Slots;
import com.agentframework.runtime.workspace.Workspace;

/**
 * 一次工作流运行所需的全部输入。
 *
 * @param agent       Agent 实例
 * @param session     会话
 * @param workflow    工作流定义
 * @param slots       槽位容器
 * @param workspace   工作区，可为 null
 * @param input       本次输入
 * @param trace       链路上下文
 * @param parentSpan  父 span，可为 null
 */
public record WorkflowExecution(
        Agent agent,
        Session session,
        WorkflowDefinition workflow,
        Slots slots,
        Workspace workspace,
        Input input,
        TraceContext trace,
        Span parentSpan) {

    /**
     * @param agent     Agent 实例
     * @param session   会话
     * @param workflow  工作流定义
     * @param slots     槽位容器
     * @param workspace 工作区，可为 null
     * @param input     本次输入
     * @return 未开启追踪的执行输入
     */
    public static WorkflowExecution of(Agent agent, Session session, WorkflowDefinition workflow, Slots slots,
            Workspace workspace, Input input) {
        return new WorkflowExecution(agent, session, workflow, slots, workspace, input, null, null);
    }

    /**
     * @param newTrace       链路上下文，可为 null
     * @param newParentSpan  父 span，可为 null
     * @return 绑定链路信息后的执行输入
     */
    public WorkflowExecution withTrace(TraceContext newTrace, Span newParentSpan) {
        return new WorkflowExecution(agent, session, workflow, slots, workspace, input, newTrace, newParentSpan);
    }
}
