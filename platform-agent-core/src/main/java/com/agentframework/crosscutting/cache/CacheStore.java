package com.agentframework.crosscutting.cache;

import java.util.Optional;

/**
 * 缓存存储扩展点。
 *
 * <p>默认提供内存实现；分布式场景可替换为 Redis 等实现，引擎无需改动。</p>
 */
public interface CacheStore {

    /**
     * 读取缓存。
     *
     * @param key 缓存键
     * @return 未命中或已过期时返回空
     */
    Optional<CacheEntry> get(String key);

    /**
     * 写入缓存。
     *
     * @param entry 缓存条目
     */
    void put(CacheEntry entry);

    /**
     * 失效单个键。
     *
     * @param key 缓存键
     * @return 是否确实移除了条目
     */
    boolean invalidate(String key);

    /**
     * 失效整个命名空间。
     *
     * @param namespace 命名空间
     * @return 被移除的条目数
     */
    int invalidateNamespace(String namespace);

    /** 清空全部缓存。 */
    void clear();

    /** @return 当前条目数 */
    int size();
}
