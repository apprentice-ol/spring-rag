package com.agentframework.runtime.event;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 领域事件：节点、会话、工具等状态变化对外发布的统一载体。
 *
 * @param id         事件 id
 * @param type       事件类型，取值见 {@link Topics}
 * @param sessionId  所属会话 id
 * @param tenantId   所属租户 id
 * @param occurredAt 发生时间
 * @param payload    事件负载
 * @param traceId    链路追踪 id
 */
public record Event(
        String id,
        String type,
        String sessionId,
        String tenantId,
        Instant occurredAt,
        Map<String, Object> payload,
        String traceId) {

    public Event {
        id = id == null ? UUID.randomUUID().toString() : id;
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        payload = payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    /**
     * 构造事件。
     *
     * @param type      事件类型
     * @param sessionId 会话 id
     * @param payload   事件负载
     * @return 事件实例
     */
    public static Event of(String type, String sessionId, Map<String, Object> payload) {
        return new Event(null, type, sessionId, null, null, payload, null);
    }

    /**
     * 追加单个负载字段。
     *
     * @param key   字段名
     * @param value 字段值
     * @return 追加负载后的事件
     */
    public Event withPayload(String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(payload);
        merged.put(key, value);
        return new Event(id, type, sessionId, tenantId, occurredAt, merged, traceId);
    }

    /**
     * @param tenantId 租户 id
     * @return 补充租户后的事件
     */
    public Event withTenant(String tenantId) {
        return new Event(id, type, sessionId, tenantId, occurredAt, payload, traceId);
    }

    /**
     * @param traceId 链路追踪 id
     * @return 补充链路 id 后的事件
     */
    public Event withTrace(String traceId) {
        return new Event(id, type, sessionId, tenantId, occurredAt, payload, traceId);
    }
}
