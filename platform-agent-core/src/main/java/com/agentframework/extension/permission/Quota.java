package com.agentframework.extension.permission;

import java.time.Duration;

/**
 * 资源配额：限制单个会话可消耗的 token、节点、工具调用、墙钟时间与内存。
 *
 * @param maxTokens       token 上限，≤0 表示不限制
 * @param maxNodes        节点执行次数上限，≤0 表示不限制
 * @param maxToolCalls    工具调用次数上限，≤0 表示不限制
 * @param maxWallTime     墙钟耗时上限，null 表示不限制
 * @param maxMemoryBytes  内存上限，≤0 表示不限制
 * @param maxCpuSeconds   CPU 时间上限，≤0 表示不限制
 */
public record Quota(
        long maxTokens,
        int maxNodes,
        int maxToolCalls,
        Duration maxWallTime,
        long maxMemoryBytes,
        double maxCpuSeconds) {

    /**
     * 由定义层配额策略转换而来。
     *
     * @param policy 定义层配额策略，可为 null
     * @return 配额；入参为 null 时返回不限制配额
     */
    public static Quota from(com.agentframework.definition.policy.QuotaPolicy policy) {
        if (policy == null) {
            return unlimited();
        }
        return new Quota(policy.maxTokens(), policy.maxNodes(), policy.maxToolCalls(), policy.maxWallTime(), -1, -1);
    }

    /** @return 不限制任何资源 */
    public static Quota unlimited() {
        return new Quota(-1, -1, -1, null, -1, -1);
    }

    /**
     * @param maxTokens    token 上限
     * @param maxNodes     节点上限
     * @param maxToolCalls 工具调用上限
     * @return 不限制内存与墙钟时间的配额
     */
    public static Quota of(long maxTokens, int maxNodes, int maxToolCalls) {
        return new Quota(maxTokens, maxNodes, maxToolCalls, null, -1, -1);
    }

    /**
     * @param maxWallTime 墙钟耗时上限
     * @return 覆盖墙钟限制后的配额
     */
    public Quota withWallTime(Duration maxWallTime) {
        return new Quota(maxTokens, maxNodes, maxToolCalls, maxWallTime, maxMemoryBytes, maxCpuSeconds);
    }

    /** @return 是否限制 token */
    public boolean tokensLimited() {
        return maxTokens > 0;
    }

    /** @return 是否限制节点数 */
    public boolean nodesLimited() {
        return maxNodes > 0;
    }

    /** @return 是否限制工具调用次数 */
    public boolean toolCallsLimited() {
        return maxToolCalls > 0;
    }

    /** @return 是否限制墙钟耗时 */
    public boolean wallTimeLimited() {
        return maxWallTime != null && !maxWallTime.isZero() && !maxWallTime.isNegative();
    }

    /** @return 是否限制内存 */
    public boolean memoryLimited() {
        return maxMemoryBytes > 0;
    }

    /** @return 是否限制 CPU 时间 */
    public boolean cpuLimited() {
        return maxCpuSeconds > 0;
    }
}
