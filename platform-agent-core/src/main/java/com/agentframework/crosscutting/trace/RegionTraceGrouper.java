package com.agentframework.crosscutting.trace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Region 感知的追踪分组器：把扁平 Span 列表整理成「区域 → Span 树」的展示结构。
 *
 * <p>分组只读取 Span 上的 {@code region} 与 {@code paradigm} 属性，不修改 Span 模型，
 * 因此可以在导出侧独立演进；未归属任何区域的 Span 归入 {@code unassigned} 分组。</p>
 */
public final class RegionTraceGrouper {

    /** Span 上的区域属性名，由运行时写入。 */
    public static final String REGION_ATTRIBUTE = "region";

    /** Span 上的范式属性名，由运行时写入。 */
    public static final String PARADIGM_ATTRIBUTE = "paradigm";

    /** 未归属区域的分组名。 */
    public static final String UNASSIGNED = "unassigned";

    /**
     * 构建分组树。
     *
     * @param traceName 根节点名，通常为 Agent 或工作流名
     * @param spans     同一链路的 Span 列表
     * @return 分组树根节点
     */
    public TraceNode group(String traceName, List<Span> spans) {
        List<Span> ordered = new ArrayList<>(spans == null ? List.of() : spans);
        Map<String, List<Span>> byRegion = new LinkedHashMap<>();
        Map<String, String> paradigms = new LinkedHashMap<>();
        for (Span span : ordered) {
            if (span == null) {
                continue;
            }
            String regionId = attribute(span, REGION_ATTRIBUTE);
            String key = regionId == null || regionId.isBlank() ? UNASSIGNED : regionId;
            byRegion.computeIfAbsent(key, ignored -> new ArrayList<>()).add(span);
            if (!UNASSIGNED.equals(key)) {
                paradigms.putIfAbsent(key, attribute(span, PARADIGM_ATTRIBUTE));
            }
        }
        List<TraceNode> regionNodes = new ArrayList<>();
        byRegion.forEach((key, regionSpans) -> {
            if (UNASSIGNED.equals(key)) {
                return;
            }
            regionNodes.add(new TraceNode(key, key, paradigms.get(key), null, buildSpanForest(regionSpans)));
        });
        List<Span> unassigned = byRegion.get(UNASSIGNED);
        if (unassigned != null && !unassigned.isEmpty()) {
            regionNodes.add(new TraceNode(UNASSIGNED, null, null, null, buildSpanForest(unassigned)));
        }
        return new TraceNode(traceName == null ? "trace" : traceName, null, null, null, regionNodes);
    }

    /**
     * 渲染为多行文本，便于日志与调试输出。
     *
     * @param root 分组树根节点
     * @return 渲染结果
     */
    public String render(TraceNode root) {
        StringBuilder builder = new StringBuilder();
        builder.append("Trace: ").append(root.label());
        for (int i = 0; i < root.children().size(); i++) {
            renderChild(builder, root.children().get(i), "", i == root.children().size() - 1);
        }
        return builder.toString();
    }

    /**
     * 渲染单个子节点。
     *
     * @param builder 输出缓冲
     * @param node    子节点
     * @param prefix  前缀
     * @param last    是否为本层最后一个节点
     */
    private void renderChild(StringBuilder builder, TraceNode node, String prefix, boolean last) {
        builder.append(System.lineSeparator())
                .append(prefix)
                .append(last ? "└─ " : "├─ ")
                .append(node.label())
                .append(detail(node));
        String childPrefix = prefix + (last ? "   " : "│  ");
        for (int i = 0; i < node.children().size(); i++) {
            renderChild(builder, node.children().get(i), childPrefix, i == node.children().size() - 1);
        }
    }

    /**
     * @param node 节点
     * @return 耗时与状态描述
     */
    private String detail(TraceNode node) {
        if (node.isGroup()) {
            return " (" + node.spanCount() + " spans)";
        }
        return " (" + node.durationMs() + "ms, " + node.span().status().name() + ")";
    }

    /**
     * 在同一分组内按 parentId 还原 Span 树；父节点不在本组时视为根。
     *
     * @param spans 分组内的 Span，保持原始顺序
     * @return 根节点列表
     */
    private List<TraceNode> buildSpanForest(List<Span> spans) {
        Set<String> ids = new LinkedHashSet<>();
        spans.forEach(span -> ids.add(span.id()));
        Map<String, List<Span>> childrenByParent = new LinkedHashMap<>();
        List<Span> roots = new ArrayList<>();
        for (Span span : spans) {
            if (span.parentId() != null && ids.contains(span.parentId())) {
                childrenByParent.computeIfAbsent(span.parentId(), ignored -> new ArrayList<>()).add(span);
            } else {
                roots.add(span);
            }
        }
        List<TraceNode> forest = new ArrayList<>();
        roots.forEach(root -> forest.add(spanNode(root, childrenByParent)));
        return forest;
    }

    /**
     * @param span           当前 Span
     * @param childrenByParent 父子索引
     * @return Span 节点
     */
    private TraceNode spanNode(Span span, Map<String, List<Span>> childrenByParent) {
        List<TraceNode> children = new ArrayList<>();
        for (Span child : childrenByParent.getOrDefault(span.id(), List.of())) {
            children.add(spanNode(child, childrenByParent));
        }
        return new TraceNode(span.name(), null, null, span, children);
    }

    /**
     * @param span      目标 Span
     * @param attribute 属性名
     * @return 属性值，缺失时返回 null
     */
    private String attribute(Span span, String attribute) {
        Object value = span.attributes().get(attribute);
        return value == null ? null : String.valueOf(value);
    }
}
