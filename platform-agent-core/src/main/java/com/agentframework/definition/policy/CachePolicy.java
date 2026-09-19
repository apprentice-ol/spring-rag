package com.agentframework.definition.policy;

import java.time.Duration;
import java.util.List;

/**
 * 缓存策略：描述结果如何缓存。
 *
 * @param enabled   是否启用缓存
 * @param ttl       缓存存活时间
 * @param strategy  键生成策略，默认按内容寻址
 * @param keyFields {@link KeyStrategy#EXPLICIT} 模式下参与拼键的字段
 */
public record CachePolicy(boolean enabled, Duration ttl, KeyStrategy strategy, List<String> keyFields) {

    /** 缓存键生成策略。 */
    public enum KeyStrategy {
        /** 按输入内容哈希生成键，无需额外声明。 */
        CONTENT,
        /** 仅使用 {@code keyFields} 声明的字段生成键。 */
        EXPLICIT
    }

    public CachePolicy {
        ttl = ttl == null ? Duration.ofMinutes(5) : ttl;
        strategy = strategy == null ? KeyStrategy.CONTENT : strategy;
        keyFields = List.copyOf(keyFields == null ? List.of() : keyFields);
    }

    /** @return 关闭缓存的策略 */
    public static CachePolicy disabled() {
        return new CachePolicy(false, Duration.ZERO, KeyStrategy.CONTENT, List.of());
    }

    /**
     * @param ttl 缓存存活时间
     * @return 内容寻址的缓存策略
     */
    public static CachePolicy content(Duration ttl) {
        return new CachePolicy(true, ttl, KeyStrategy.CONTENT, List.of());
    }

    /**
     * @param ttl       缓存存活时间
     * @param keyFields 参与拼键的字段名
     * @return 显式字段寻址的缓存策略
     */
    public static CachePolicy explicit(Duration ttl, String... keyFields) {
        return new CachePolicy(true, ttl, KeyStrategy.EXPLICIT, List.of(keyFields));
    }

    /**
     * 切换为显式字段寻址。
     *
     * @param fields 参与拼键的字段名
     * @return 新的缓存策略
     */
    public CachePolicy withKeyFields(String... fields) {
        return new CachePolicy(true, ttl, KeyStrategy.EXPLICIT, List.of(fields));
    }
}
