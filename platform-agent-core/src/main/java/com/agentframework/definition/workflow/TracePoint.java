package com.agentframework.definition.workflow;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 埋点标记：追踪器会把它转成 span 或 span 事件。
 *
 * @param name       标记名
 * @param attributes 附加属性
 */
public record TracePoint(String name, Map<String, Object> attributes) {

    public TracePoint {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("trace point name is required");
        }
        attributes = attributes == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    /**
     * @param name 标记名
     * @return 无附加属性的埋点
     */
    public static TracePoint of(String name) {
        return new TracePoint(name, null);
    }
}
