package com.agentframework.definition.view;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 动态路由视图：某节点允许自主选择的目标。
 *
 * @param from           起点节点
 * @param allowedTargets 允许的目标
 */
public record DynamicView(String from, List<String> allowedTargets) {

    public DynamicView {
        allowedTargets = List.copyOf(allowedTargets == null ? List.of() : allowedTargets);
    }

    /** @return 可序列化文档 */
    public Map<String, Object> toDocument() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("from", from);
        document.put("allowedTargets", allowedTargets);
        return document;
    }
}
