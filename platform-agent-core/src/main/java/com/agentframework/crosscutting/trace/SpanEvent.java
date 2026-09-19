package com.agentframework.crosscutting.trace;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Span 事件：Span 内部的时间点标记，例如缓存命中、重试第 N 次。
 *
 * @param name       事件名
 * @param at         发生时间
 * @param attributes 附加属性
 */
public record SpanEvent(String name, Instant at, Map<String, Object> attributes) {

    public SpanEvent {
        name = name == null ? "event" : name;
        at = at == null ? Instant.now() : at;
        attributes = attributes == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    /**
     * @param name 事件名
     * @return 无属性事件
     */
    public static SpanEvent of(String name) {
        return new SpanEvent(name, null, null);
    }
}
