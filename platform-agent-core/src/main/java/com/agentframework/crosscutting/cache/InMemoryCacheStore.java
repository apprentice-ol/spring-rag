package com.agentframework.crosscutting.cache;

import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 内存缓存实现：LRU 淘汰 + 读时惰性过期。
 *
 * <p>适合单进程运行与测试；多实例部署应替换为共享缓存实现。</p>
 */
public final class InMemoryCacheStore implements CacheStore {

    private final int maxEntries;
    private final Map<String, CacheEntry> entries;

    /**
     * @param maxEntries 最大条目数，≤0 表示不限制
     */
    public InMemoryCacheStore(int maxEntries) {
        this.maxEntries = maxEntries;
        this.entries = new LinkedHashMap<>(16, 0.75f, true);
    }

    /** 使用默认上限（1000 条）构造。 */
    public InMemoryCacheStore() {
        this(1000);
    }

    @Override
    public synchronized Optional<CacheEntry> get(String key) {
        CacheEntry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.expired(Instant.now())) {
            entries.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    @Override
    public synchronized void put(CacheEntry entry) {
        if (entry == null) {
            return;
        }
        entries.put(entry.key(), entry);
        purgeExpired();
        if (maxEntries > 0 && entries.size() > maxEntries) {
            Iterator<String> iterator = entries.keySet().iterator();
            while (entries.size() > maxEntries && iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
        }
    }

    @Override
    public synchronized boolean invalidate(String key) {
        return entries.remove(key) != null;
    }

    @Override
    public synchronized int invalidateNamespace(String namespace) {
        int before = entries.size();
        entries.values().removeIf(entry -> namespace == null || namespace.equals(entry.namespace()));
        return before - entries.size();
    }

    @Override
    public synchronized void clear() {
        entries.clear();
    }

    @Override
    public synchronized int size() {
        purgeExpired();
        return entries.size();
    }

    /** 移除全部过期条目。 */
    private void purgeExpired() {
        Instant now = Instant.now();
        entries.values().removeIf(entry -> entry.expired(now));
    }
}
