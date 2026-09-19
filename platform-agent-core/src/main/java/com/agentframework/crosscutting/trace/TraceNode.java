package com.agentframework.crosscutting.trace;

import java.util.List;

/**
 * 追踪树节点：既可以是分组节点（Region / 根），也可以是单个 Span 节点。
 *
 * <p>分组节点不持有 Span，只负责组织子节点；Span 节点持有真实 Span，并保留父子层级。</p>
 */
public final class TraceNode {

    private final String name;
    private final String regionId;
    private final String paradigm;
    private final Span span;
    private final List<TraceNode> children;

    TraceNode(String name, String regionId, String paradigm, Span span, List<TraceNode> children) {
        this.name = name == null ? "node" : name;
        this.regionId = regionId;
        this.paradigm = paradigm;
        this.span = span;
        this.children = List.copyOf(children == null ? List.of() : children);
    }

    /** @return 显示名 */
    public String name() {
        return name;
    }

    /** @return 分组所属的 Region id，Span 节点与根节点为 null */
    public String regionId() {
        return regionId;
    }

    /** @return 范式标签，未声明时为 null */
    public String paradigm() {
        return paradigm;
    }

    /** @return 持有的 Span，分组节点为 null */
    public Span span() {
        return span;
    }

    /** @return 子节点 */
    public List<TraceNode> children() {
        return children;
    }

    /** @return 是否为分组节点 */
    public boolean isGroup() {
        return span == null;
    }

    /** @return 递归统计的 Span 数量 */
    public int spanCount() {
        int count = span == null ? 0 : 1;
        for (TraceNode child : children) {
            count += child.spanCount();
        }
        return count;
    }

    /** @return 自身 Span 的耗时毫秒数，分组节点返回 0 */
    public long durationMs() {
        return span == null ? 0L : span.duration().toMillis();
    }

    /** @return 渲染用的标签：分组节点带 Region 前缀 */
    public String label() {
        if (span != null) {
            return span.name();
        }
        if (regionId == null) {
            return name;
        }
        return "[" + (paradigm == null || paradigm.isBlank() ? "region" : paradigm) + "] " + regionId;
    }
}
