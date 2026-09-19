package com.agentframework.crosscutting.trace;

import java.util.ArrayList;
import java.util.List;

/**
 * Trace 视图装配器：把 {@link TraceNode} 树转换为前端友好的结构。
 */
public final class TraceViewFactory {

    private TraceViewFactory() {
    }

    /**
     * @param node 分组树节点
     * @return 视图
     */
    public static TraceView of(TraceNode node) {
        if (node == null) {
            return null;
        }
        List<TraceView> children = new ArrayList<>();
        for (TraceNode child : node.children()) {
            children.add(of(child));
        }
        Span span = node.span();
        return new TraceView(node.label(), node.regionId(), node.paradigm(),
                span == null ? null : span.name(),
                span == null ? null : span.status().name(),
                node.durationMs(), node.spanCount(), children);
    }

}
