package com.agentframework.definition.policy;

import java.time.Duration;

/**
 * 重试策略：工具调用与模型调用共用的指数退避配置。
 *
 * @param maxAttempts    最大尝试次数，含首次调用，必须 ≥ 1
 * @param initialBackoff 第二次尝试前的初始退避时间
 * @param multiplier     退避倍数，默认 2.0
 * @param maxBackoff     单次退避上限
 */
public record RetryPolicy(int maxAttempts, Duration initialBackoff, double multiplier, Duration maxBackoff) {

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1, was " + maxAttempts);
        }
        initialBackoff = initialBackoff == null ? Duration.ZERO : initialBackoff;
        multiplier = multiplier <= 0 ? 2.0 : multiplier;
        maxBackoff = maxBackoff == null ? Duration.ofSeconds(30) : maxBackoff;
    }

    /** @return 不重试的策略（仅尝试一次） */
    public static RetryPolicy none() {
        return new RetryPolicy(1, Duration.ZERO, 2.0, Duration.ZERO);
    }

    /**
     * @param maxAttempts 最大尝试次数
     * @return 初始退避 50ms、倍数 2.0 的重试策略
     */
    public static RetryPolicy of(int maxAttempts) {
        return new RetryPolicy(maxAttempts, Duration.ofMillis(50), 2.0, Duration.ofSeconds(2));
    }

    /**
     * @param maxAttempts    最大尝试次数
     * @param initialBackoff 初始退避时间
     * @return 指定初始退避的重试策略
     */
    public static RetryPolicy of(int maxAttempts, Duration initialBackoff) {
        return new RetryPolicy(maxAttempts, initialBackoff, 2.0, Duration.ofSeconds(30));
    }

    /** @return 是否真正启用重试（尝试次数大于 1） */
    public boolean enabled() {
        return maxAttempts > 1;
    }

    /**
     * 计算第 N 次尝试前的退避时间。
     *
     * @param attempt 从 1 开始的尝试序号，1 表示首次调用（不退避）
     * @return 该次尝试前应等待的时长
     */
    public Duration backoffFor(int attempt) {
        if (attempt <= 1) {
            return Duration.ZERO;
        }
        double factor = Math.pow(multiplier, attempt - 2);
        long millis = (long) (initialBackoff.toMillis() * factor);
        return Duration.ofMillis(Math.min(millis, maxBackoff.toMillis()));
    }

    /**
     * 调整退避上限。
     *
     * @param cap 新的退避上限
     * @return 新的策略实例
     */
    public RetryPolicy withMaxBackoff(Duration cap) {
        return new RetryPolicy(maxAttempts, initialBackoff, multiplier, cap);
    }
}
