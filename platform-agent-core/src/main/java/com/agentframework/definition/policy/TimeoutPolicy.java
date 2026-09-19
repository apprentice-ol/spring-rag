package com.agentframework.definition.policy;

import java.time.Duration;

/**
 * 超时策略：单个执行单元（节点 / 工具调用 / 模型调用）的硬性截止时间。
 *
 * @param timeout 超时时长，零或空表示不限制
 */
public record TimeoutPolicy(Duration timeout) {

    public TimeoutPolicy {
        timeout = timeout == null ? Duration.ZERO : timeout;
    }

    /** @return 不限制超时的策略 */
    public static TimeoutPolicy none() {
        return new TimeoutPolicy(Duration.ZERO);
    }

    /**
     * @param timeout 超时时长
     * @return 新的超时策略
     */
    public static TimeoutPolicy of(Duration timeout) {
        return new TimeoutPolicy(timeout);
    }

    /**
     * @param seconds 超时秒数
     * @return 新的超时策略
     */
    public static TimeoutPolicy ofSeconds(long seconds) {
        return new TimeoutPolicy(Duration.ofSeconds(seconds));
    }

    /** @return 是否启用了超时控制 */
    public boolean enabled() {
        return !timeout.isZero() && !timeout.isNegative();
    }
}
