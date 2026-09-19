package com.agentframework.extension.permission;

import java.time.Duration;

/**
 * 配额用量快照。
 *
 * @param sessionId   会话 id
 * @param tokens      已消耗 token
 * @param nodes       已执行节点数
 * @param toolCalls   已执行工具调用数
 * @param wallTime    已消耗墙钟时间
 * @param memoryBytes 观测到的内存峰值
 * @param cpuSeconds  已消耗 CPU 时间
 */
public record QuotaUsage(
        String sessionId,
        long tokens,
        int nodes,
        int toolCalls,
        Duration wallTime,
        long memoryBytes,
        double cpuSeconds) {

    public QuotaUsage {
        wallTime = wallTime == null ? Duration.ZERO : wallTime;
    }

    /**
     * @param sessionId 会话 id
     * @return 空用量
     */
    public static QuotaUsage empty(String sessionId) {
        return new QuotaUsage(sessionId, 0L, 0, 0, Duration.ZERO, 0L, 0d);
    }

    /**
     * @param delta token 增量
     * @return 累加后的用量
     */
    public QuotaUsage plusTokens(long delta) {
        return new QuotaUsage(sessionId, tokens + delta, nodes, toolCalls, wallTime, memoryBytes, cpuSeconds);
    }

    /** @return 节点数加一后的用量 */
    public QuotaUsage plusNode() {
        return new QuotaUsage(sessionId, tokens, nodes + 1, toolCalls, wallTime, memoryBytes, cpuSeconds);
    }

    /** @return 工具调用数加一后的用量 */
    public QuotaUsage plusToolCall() {
        return new QuotaUsage(sessionId, tokens, nodes, toolCalls + 1, wallTime, memoryBytes, cpuSeconds);
    }

    /**
     * @param wallTime 墙钟耗时
     * @return 覆盖墙钟耗时后的用量
     */
    public QuotaUsage withWallTime(Duration wallTime) {
        return new QuotaUsage(sessionId, tokens, nodes, toolCalls, wallTime, memoryBytes, cpuSeconds);
    }
}
