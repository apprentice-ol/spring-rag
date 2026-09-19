package com.agentframework.definition.view;

import com.agentframework.definition.workflow.Edge;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 边视图。
 *
 * @param from      起点
 * @param to        终点
 * @param kind      边类型
 * @param condition 条件表达式，可为 null
 * @param label     标签，可为 null
 */
public record EdgeView(String from, String to, EdgeKind kind, String condition, String label) {

    /**
     * @param edge 边定义
     * @return 视图
     */
    public static EdgeView of(Edge edge) {
        return new EdgeView(edge.from(), edge.to(),
                edge.isConditional() ? EdgeKind.CONDITION : EdgeKind.STATIC,
                edge.condition(), edge.label());
    }

    /**
     * @param from   起点
     * @param to     终点
     * @return 动态边视图
     */
    public static EdgeView dynamic(String from, String to) {
        return new EdgeView(from, to, EdgeKind.DYNAMIC, null, "dynamic");
    }

    /** @return 可序列化文档 */
    public Map<String, Object> toDocument() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("from", from);
        document.put("to", to);
        document.put("kind", kind.name());
        document.put("condition", condition);
        document.put("label", label);
        return document;
    }
}
