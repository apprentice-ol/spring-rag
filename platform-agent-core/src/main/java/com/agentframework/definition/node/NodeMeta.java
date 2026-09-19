package com.agentframework.definition.node;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 节点横切信息：属性、守卫引用与过滤器引用。
 *
 * @param attributes 节点自定义属性，供执行器读取
 * @param guardRefs  该节点前后需要执行的守卫名称
 * @param filterRefs 该节点输入输出需要执行的过滤器名称
 */
public record NodeMeta(Map<String, Object> attributes, List<String> guardRefs, List<String> filterRefs) {

    public NodeMeta {
        attributes = attributes == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        guardRefs = List.copyOf(guardRefs == null ? List.of() : guardRefs);
        filterRefs = List.copyOf(filterRefs == null ? List.of() : filterRefs);
    }

    public static NodeMeta empty() {
        return new NodeMeta(null, null, null);
    }

    public static NodeMeta attributes(Map<String, Object> attributes) {
        return new NodeMeta(attributes, null, null);
    }

    public NodeMeta withAttribute(String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(attributes);
        merged.put(key, value);
        return new NodeMeta(merged, guardRefs, filterRefs);
    }

    public NodeMeta withGuards(String... refs) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(guardRefs);
        merged.addAll(List.of(refs));
        return new NodeMeta(attributes, List.copyOf(merged), filterRefs);
    }

    public NodeMeta withFilters(String... refs) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(filterRefs);
        merged.addAll(List.of(refs));
        return new NodeMeta(attributes, guardRefs, List.copyOf(merged));
    }

    public Object attribute(String key) {
        return attributes.get(key);
    }

    public String stringAttribute(String key, String fallback) {
        Object value = attributes.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    public int intAttribute(String key, int fallback) {
        Object value = attributes.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return value == null ? fallback : Integer.parseInt(String.valueOf(value));
    }

    public boolean booleanAttribute(String key, boolean fallback) {
        Object value = attributes.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }
}
