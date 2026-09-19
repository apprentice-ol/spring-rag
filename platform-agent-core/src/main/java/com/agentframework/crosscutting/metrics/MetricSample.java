package com.agentframework.crosscutting.metrics;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 指标采样点。
 *
 * @param name  指标名
 * @param type  指标类型
 * @param value 采样值
 * @param tags  标签，例如 agent / node / tool
 * @param at    采样时间
 */
public record MetricSample(String name, MetricType type, double value, Map<String, Object> tags, Instant at) {

    public MetricSample {
        name = name == null ? "unnamed" : name;
        type = type == null ? MetricType.COUNTER : type;
        tags = tags == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(tags));
        at = at == null ? Instant.now() : at;
    }
}
