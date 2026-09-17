package com.jjx.customer.platform.cache;

import java.time.Duration;
import java.util.Optional;

/**
 * 缓存存取接口（String JSON in / String JSON out）：装饰器只管业务语义（何时查/何时写/写什么），
 * 存取与降级由实现承担。单测用内存 fake 实现，生产走 Redis。
 */
public interface CacheStore {

    /**
     * 读缓存。Redis 不可用/未启用一律返回 empty（优雅降级，绝不抛异常阻断业务）。
     *
     * @param layer 层名（intent/retrieval/logs/answer/embedding），仅用于打点
     */
    Optional<String> get(String key, String layer);

    /**
     * 写缓存（带 TTL）。失败静默（warn 节流），绝不抛异常。
     */
    void put(String key, String json, Duration ttl, String layer);

    /**
     * 延长 TTL（重设过期时间；热度策略命中时由装饰器调用）。失败静默，绝不抛异常。
     */
    void touch(String key, Duration ttl, String layer);
}
