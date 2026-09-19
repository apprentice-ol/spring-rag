package com.agentframework.crosscutting.interceptor;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 拦截器上下文：控制管道做重试、超时、缓存、追踪所需的元信息。
 *
 * @param phase     挂载点
 * @param sessionId 会话 id
 * @param nodeId    节点 id，可为 null
 * @param operation 操作名，例如 {@code llm:plan}、{@code tool:search}
 * @param attributes 附加属性，常用键见 {@link InterceptorAttributes}
 * @param startedAt 进入管道的时间
 */
public record InterceptorContext(
        InterceptorPhase phase,
        String sessionId,
        String nodeId,
        String operation,
        Map<String, Object> attributes,
        Instant startedAt) {

    public InterceptorContext {
        phase = phase == null ? InterceptorPhase.AROUND_NODE : phase;
        operation = operation == null ? "operation" : operation;
        attributes = attributes == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        startedAt = startedAt == null ? Instant.now() : startedAt;
    }

    /**
     * @param phase     挂载点
     * @param operation 操作名
     * @return 仅含必需字段的上下文
     */
    public static InterceptorContext of(InterceptorPhase phase, String operation) {
        return new InterceptorContext(phase, null, null, operation, null, null);
    }

    /**
     * @param sessionId 会话 id
     * @param nodeId    节点 id
     * @return 补充归属信息后的上下文
     */
    public InterceptorContext withOwner(String sessionId, String nodeId) {
        return new InterceptorContext(phase, sessionId, nodeId, operation, attributes, startedAt);
    }

    /**
     * 替换挂载点，其余字段原样保留。
     *
     * @param newPhase 新挂载点
     * @return 替换挂载点后的上下文
     */
    public InterceptorContext withPhase(InterceptorPhase newPhase) {
        return new InterceptorContext(newPhase, sessionId, nodeId, operation, attributes, startedAt);
    }

    /**
     * @param key   属性名
     * @param value 属性值
     * @return 追加属性后的上下文
     */
    public InterceptorContext withAttribute(String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(attributes);
        merged.put(key, value);
        return new InterceptorContext(phase, sessionId, nodeId, operation, merged, startedAt);
    }

    /**
     * 批量追加属性。
     *
     * @param extra 待追加的键值对
     * @return 追加属性后的上下文
     */
    public InterceptorContext withAttributes(Map<String, Object> extra) {
        if (extra == null || extra.isEmpty()) {
            return this;
        }
        Map<String, Object> merged = new LinkedHashMap<>(attributes);
        merged.putAll(extra);
        return new InterceptorContext(phase, sessionId, nodeId, operation, merged, startedAt);
    }

    /**
     * 读取属性。
     *
     * @param key 属性名
     * @param <T> 期望类型
     * @return 属性值，不存在返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T attribute(String key) {
        return (T) attributes.get(key);
    }

    /**
     * 读取属性并做类型转换。
     *
     * @param key  属性名
     * @param type 期望类型
     * @param <T>  类型参数
     * @return 属性值，类型不匹配或不存在时返回 null
     */
    public <T> T attribute(String key, Class<T> type) {
        Object value = attributes.get(key);
        return type.isInstance(value) ? type.cast(value) : null;
    }
}
