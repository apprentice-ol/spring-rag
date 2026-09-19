package com.agentframework.crosscutting.trace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Trace 视图：前端只需按 children 递归渲染，无需理解 TraceNode 结构。
 *
 * @param name       显示名（区域组为 {@code [范式] 区域}）
 * @param regionId   所属区域，可为 null
 * @param paradigm   范式标签，可为 null
 * @param spanName   Span 名，分组节点为 null
 * @param status     Span 状态，分组节点为 null
 * @param durationMs 耗时毫秒，分组节点为 0
 * @param spanCount  递归统计的 Span 数量
 * @param children   子节点
 */
public record TraceView(
        String name,
        String regionId,
        String paradigm,
        String spanName,
        String status,
        long durationMs,
        int spanCount,
        List<TraceView> children) {

    public TraceView {
        children = List.copyOf(children == null ? List.of() : children);
    }

    /** @return 可序列化文档 */
    public Map<String, Object> toDocument() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("name", name);
        document.put("regionId", regionId);
        document.put("paradigm", paradigm);
        document.put("spanName", spanName);
        document.put("status", status);
        document.put("durationMs", durationMs);
        document.put("spanCount", spanCount);
        document.put("children", children.stream().map(TraceView::toDocument).toList());
        return document;
    }
}
