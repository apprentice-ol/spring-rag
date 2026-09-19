package com.agentframework.definition.policy;

import java.time.Duration;

/**
 * 配额策略：单个会话的资源上限。取值为 {@code -1} 或 {@code null} 表示不限制。
 *
 * @param maxTokens    token 上限
 * @param maxNodes     节点执行次数上限
 * @param maxToolCalls 工具调用次数上限
 * @param maxWallTime  墙钟耗时上限
 */
public record QuotaPolicy(long maxTokens, int maxNodes, int maxToolCalls, Duration maxWallTime) {

    public static final long UNLIMITED = -1L;

    /** @return 不限制任何资源的配额 */
    public static QuotaPolicy unlimited() {
        return new QuotaPolicy(UNLIMITED, (int) UNLIMITED, (int) UNLIMITED, null);
    }

    /**
     * @param maxTokens   token 上限
     * @param maxNodes    节点执行次数上限
     * @param maxToolCalls 工具调用次数上限
     * @return 不限制墙钟时间的配额
     */
    public static QuotaPolicy of(long maxTokens, int maxNodes, int maxToolCalls) {
        return new QuotaPolicy(maxTokens, maxNodes, maxToolCalls, null);
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
}
