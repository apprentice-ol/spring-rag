package com.agentframework.engine.core;

import com.agentframework.infra.modelgateway.Usage;
import com.agentframework.runtime.session.Cursor;
import com.agentframework.runtime.session.Message;
import com.agentframework.runtime.session.SessionState;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次运行的返回结果：应用层只需要它就能完成渲染与持久化。
 *
 * @param sessionId     会话 id
 * @param state         结束状态
 * @param output        最终输出
 * @param cursor        结束时的游标
 * @param messages      会话消息
 * @param usage         token 用量
 * @param duration      总耗时
 * @param slots         槽位快照
 * @param visitedNodes  执行路径上的节点
 * @param suspendedNode 挂起的节点 id，未挂起为 null
 * @param error         失败原因
 * @param executionTrace 逐步执行轨迹
 */
public record RunResult(
        String sessionId,
        SessionState state,
        String output,
        Cursor cursor,
        List<Message> messages,
        Usage usage,
        Duration duration,
        Map<String, Object> slots,
        List<String> visitedNodes,
        String suspendedNode,
        String error,
        List<ExecutionTraceStep> executionTrace) {

    public RunResult {
        state = state == null ? SessionState.COMPLETED : state;
        output = output == null ? "" : output;
        messages = List.copyOf(messages == null ? List.of() : messages);
        usage = usage == null ? Usage.zero() : usage;
        duration = duration == null ? Duration.ZERO : duration;
        slots = slots == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(slots));
        visitedNodes = List.copyOf(visitedNodes == null ? List.of() : visitedNodes);
        executionTrace = List.copyOf(executionTrace == null ? List.of() : executionTrace);
    }

    /**
     * @param sessionId 会话 id
     * @param state     状态
     * @param output    输出
     * @return 简化结果
     */
    public static RunResult of(String sessionId, SessionState state, String output) {
        return new RunResult(sessionId, state, output, null, null, null, null, null, null, null, null, null);
    }

    /**
     * @param newCursor 结束时的游标
     * @return 覆盖游标后的结果
     */
    public RunResult withCursor(Cursor newCursor) {
        return new RunResult(sessionId, state, output, newCursor, messages, usage, duration, slots, visitedNodes, suspendedNode, error, executionTrace);
    }

    /**
     * @param newMessages 会话消息
     * @return 覆盖消息后的结果
     */
    public RunResult withMessages(List<Message> newMessages) {
        return new RunResult(sessionId, state, output, cursor, newMessages, usage, duration, slots, visitedNodes, suspendedNode, error, executionTrace);
    }

    /**
     * @param newUsage token 用量
     * @return 覆盖用量后的结果
     */
    public RunResult withUsage(Usage newUsage) {
        return new RunResult(sessionId, state, output, cursor, messages, newUsage, duration, slots, visitedNodes, suspendedNode, error, executionTrace);
    }

    /**
     * @param newDuration 总耗时
     * @return 覆盖耗时后的结果
     */
    public RunResult withDuration(Duration newDuration) {
        return new RunResult(sessionId, state, output, cursor, messages, usage, newDuration, slots, visitedNodes, suspendedNode, error, executionTrace);
    }

    /**
     * @param newSlots 槽位快照
     * @return 覆盖槽位后的结果
     */
    public RunResult withSlots(Map<String, Object> newSlots) {
        return new RunResult(sessionId, state, output, cursor, messages, usage, duration, newSlots, visitedNodes, suspendedNode, error, executionTrace);
    }

    /**
     * @param newVisitedNodes 执行路径上的节点
     * @return 覆盖执行路径后的结果
     */
    public RunResult withVisitedNodes(List<String> newVisitedNodes) {
        return new RunResult(sessionId, state, output, cursor, messages, usage, duration, slots, newVisitedNodes, suspendedNode, error, executionTrace);
    }

    /**
     * @param newSuspendedNode 挂起的节点 id
     * @return 覆盖挂起节点后的结果
     */
    public RunResult withSuspendedNode(String newSuspendedNode) {
        return new RunResult(sessionId, state, output, cursor, messages, usage, duration, slots, visitedNodes, newSuspendedNode, error, executionTrace);
    }

    /**
     * @param newError 失败原因
     * @return 覆盖失败原因后的结果
     */
    public RunResult withError(String newError) {
        return new RunResult(sessionId, state, output, cursor, messages, usage, duration, slots, visitedNodes, suspendedNode, newError, executionTrace);
    }

    /**
     * @param newTrace 逐步执行轨迹
     * @return 覆盖执行轨迹后的结果
     */
    public RunResult withExecutionTrace(List<ExecutionTraceStep> newTrace) {
        return new RunResult(sessionId, state, output, cursor, messages, usage, duration, slots, visitedNodes,
                suspendedNode, error, newTrace);
    }

    /** @return 是否执行成功 */
    public boolean successful() {
        return state == SessionState.COMPLETED;
    }

    /** @return 是否等待外部输入 */
    public boolean suspended() {
        return state == SessionState.SUSPENDED;
    }
}
