package com.agentframework.engine.core;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 引擎配置：影响所有会话的全局参数。
 *
 * @param maxSteps        单次运行的最大节点步数，防止工作流死循环
 * @param nodeTimeout     节点默认超时
 * @param traceEnabled    是否启用链路追踪
 * @param cacheEnabled    是否启用缓存拦截器
 * @param metricsEnabled  是否启用指标采集
 * @param dynamicRoutingEnabled 是否允许动态路由（节点自主选路）
 * @param persistSessions 是否持久化会话（临时会话会覆盖此设置）
 * @param attributes      自定义属性
 */
public record EngineConfig(
        int maxSteps,
        Duration nodeTimeout,
        boolean traceEnabled,
        boolean cacheEnabled,
        boolean metricsEnabled,
        boolean dynamicRoutingEnabled,
        boolean persistSessions,
        Map<String, Object> attributes) {

    public EngineConfig {
        maxSteps = maxSteps <= 0 ? 100 : maxSteps;
        nodeTimeout = nodeTimeout == null ? Duration.ofSeconds(60) : nodeTimeout;
        attributes = attributes == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    /** @return 默认配置：100 步上限、开启追踪/缓存/指标、持久化会话 */
    public static EngineConfig defaults() {
        return new EngineConfig(0, null, true, true, true, true, true, null);
    }

    /** @return 全功能关闭的配置，用于纯内存试跑 */
    public static EngineConfig minimal() {
        return new EngineConfig(0, null, false, false, false, true, false, null);
    }

    /**
     * @param maxSteps 步数上限
     * @return 覆盖步数上限后的配置
     */
    public EngineConfig withMaxSteps(int maxSteps) {
        return new EngineConfig(maxSteps, nodeTimeout, traceEnabled, cacheEnabled, metricsEnabled,
                dynamicRoutingEnabled,
                persistSessions, attributes);
    }

    /**
     * @param persistSessions 是否持久化会话
     * @return 覆盖持久化开关后的配置
     */
    public EngineConfig withPersistence(boolean persistSessions) {
        return new EngineConfig(maxSteps, nodeTimeout, traceEnabled, cacheEnabled, metricsEnabled,
                dynamicRoutingEnabled, persistSessions, attributes);
    }

    /**
     * @return 关闭动态路由后的配置
     */
    public EngineConfig withoutDynamicRouting() {
        return new EngineConfig(maxSteps, nodeTimeout, traceEnabled, cacheEnabled, metricsEnabled, false,
                persistSessions, attributes);
    }

    /**
     * @param key   属性名
     * @param value 属性值
     * @return 追加属性后的配置
     */
    public EngineConfig withAttribute(String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(attributes);
        merged.put(key, value);
        return new EngineConfig(maxSteps, nodeTimeout, traceEnabled, cacheEnabled, metricsEnabled,
                dynamicRoutingEnabled, persistSessions, merged);
    }
}
