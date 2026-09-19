package com.agentframework.engine.middleware;

import com.agentframework.crosscutting.cache.CacheKeyBuilder;
import com.agentframework.crosscutting.cache.CacheStore;
import com.agentframework.crosscutting.cache.ContentHashCacheKeyBuilder;
import com.agentframework.crosscutting.interceptor.Interceptor;
import com.agentframework.crosscutting.interceptor.Interceptors;
import com.agentframework.crosscutting.metrics.Metrics;
import com.agentframework.runtime.event.EventBus;

/**
 * 缓存中间件：把缓存拦截器装配进管道的快捷入口。
 */
public final class CacheMiddleware {

    private CacheMiddleware() {
    }

    /**
     * @param store   缓存存储
     * @param events  事件总线，可为 null
     * @param metrics 指标采集器，可为 null
     * @return 缓存拦截器
     */
    public static Interceptor create(CacheStore store, EventBus events, Metrics metrics) {
        return create(store, new ContentHashCacheKeyBuilder(), events, metrics);
    }

    /**
     * @param store      缓存存储
     * @param keyBuilder 缓存键构建器
     * @param events     事件总线，可为 null
     * @param metrics    指标采集器，可为 null
     * @return 缓存拦截器
     */
    public static Interceptor create(CacheStore store, CacheKeyBuilder keyBuilder, EventBus events, Metrics metrics) {
        return new Interceptors.Cache(store, keyBuilder, events, metrics);
    }
}
