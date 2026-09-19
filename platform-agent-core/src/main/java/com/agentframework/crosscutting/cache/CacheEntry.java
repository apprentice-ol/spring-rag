package com.agentframework.crosscutting.cache;

import java.time.Duration;
import java.time.Instant;

/**
 * 缓存条目。
 *
 * @param key       缓存键
 * @param namespace 命名空间（通常是 会话 / Agent / 工具名）
 * @param value     缓存值
 * @param createdAt 写入时间
 * @param expiresAt 过期时间，null 表示不过期
 */
public record CacheEntry(String key, String namespace, Object value, Instant createdAt, Instant expiresAt) {

    public CacheEntry {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("缓存键不能为空");
        }
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    /**
     * 构造带 TTL 的条目。
     *
     * @param key       缓存键
     * @param namespace 命名空间
     * @param value     缓存值
     * @param ttl       存活时间，零或负表示不过期
     * @return 缓存条目
     */
    public static CacheEntry of(String key, String namespace, Object value, Duration ttl) {
        Instant now = Instant.now();
        Instant expiresAt = ttl == null || ttl.isZero() || ttl.isNegative() ? null : now.plus(ttl);
        return new CacheEntry(key, namespace, value, now, expiresAt);
    }

    /**
     * @param now 判断时刻
     * @return 是否已过期
     */
    public boolean expired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    /** @return 是否已过期 */
    public boolean expired() {
        return expired(Instant.now());
    }
}
