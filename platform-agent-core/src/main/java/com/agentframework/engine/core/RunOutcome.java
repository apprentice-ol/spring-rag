package com.agentframework.engine.core;

import com.agentframework.infra.modelgateway.Usage;
import com.agentframework.runtime.session.Cursor;
import com.agentframework.runtime.session.Message;
import com.agentframework.runtime.session.SessionState;
import java.util.List;

/**
 * 工作流运行的内部结果，由运行时产出、由引擎翻译成 {@link RunResult}。
 *
 * @param state         结束状态
 * @param output        最终输出
 * @param cursor        结束游标
 * @param messages      新增消息
 * @param usage         token 用量
 * @param visitedNodes  执行路径
 * @param suspendedNode 挂起节点 id，未挂起为 null
 * @param error         失败原因
 * @param trace         逐步执行轨迹
 */
public record RunOutcome(
        SessionState state,
        String output,
        Cursor cursor,
        List<Message> messages,
        Usage usage,
        List<String> visitedNodes,
        String suspendedNode,
        String error,
        List<ExecutionTraceStep> trace) {

    public RunOutcome {
        state = state == null ? SessionState.COMPLETED : state;
        output = output == null ? "" : output;
        messages = List.copyOf(messages == null ? List.of() : messages);
        usage = usage == null ? Usage.zero() : usage;
        visitedNodes = List.copyOf(visitedNodes == null ? List.of() : visitedNodes);
        trace = List.copyOf(trace == null ? List.of() : trace);
    }

    /**
     * @param output  输出
     * @param cursor  游标
     * @param visited 执行路径
     * @return 完成结果
     */
    public static RunOutcome completed(String output, Cursor cursor, List<String> visited) {
        return completed(output, cursor, visited, List.of());
    }

    /**
     * @param output  输出
     * @param cursor  游标
     * @param visited 执行路径
     * @param trace   执行轨迹
     * @return 完成结果
     */
    public static RunOutcome completed(String output, Cursor cursor, List<String> visited,
            List<ExecutionTraceStep> trace) {
        return new RunOutcome(SessionState.COMPLETED, output, cursor, null, null, visited, null, null, trace);
    }

    /**
     * @param nodeId  挂起节点
     * @param prompt  挂起提示
     * @param cursor  游标
     * @param visited 执行路径
     * @return 挂起结果
     */
    public static RunOutcome suspended(String nodeId, String prompt, Cursor cursor, List<String> visited) {
        return suspended(nodeId, prompt, cursor, visited, List.of());
    }

    /**
     * @param nodeId  挂起节点
     * @param prompt  挂起提示
     * @param cursor  游标
     * @param visited 执行路径
     * @param trace   执行轨迹
     * @return 挂起结果
     */
    public static RunOutcome suspended(String nodeId, String prompt, Cursor cursor, List<String> visited,
            List<ExecutionTraceStep> trace) {
        return new RunOutcome(SessionState.SUSPENDED, prompt, cursor, null, null, visited, nodeId, null, trace);
    }

    /**
     * @param reason  失败原因
     * @param cursor  游标
     * @param visited 执行路径
     * @return 失败结果
     */
    public static RunOutcome failed(String reason, Cursor cursor, List<String> visited) {
        return failed(reason, cursor, visited, List.of());
    }

    /**
     * @param reason  失败原因
     * @param cursor  游标
     * @param visited 执行路径
     * @param trace   执行轨迹
     * @return 失败结果
     */
    public static RunOutcome failed(String reason, Cursor cursor, List<String> visited,
            List<ExecutionTraceStep> trace) {
        return new RunOutcome(SessionState.FAILED, "", cursor, null, null, visited, null, reason, trace);
    }
}
