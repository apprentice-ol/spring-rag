package com.agentframework.definition.region;

import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.node.NodeType;
import com.agentframework.definition.workflow.Edge;
import com.agentframework.definition.workflow.WorkflowDefinition;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Region 标注器：只产出建议，不产生任何执行影响。
 *
 * <p>推断依据分两级：节点类型映射（确定性高）与回边检测（启发式）。
 * 建议是否被采纳由使用者决定；采纳方式是在工作流中显式声明 Region。</p>
 */
public final class RegionAnnotator {

    /** 节点类型到默认范式的映射。 */
    private static final Map<NodeType, Paradigm> TYPE_MAPPING =
            Map.of(
                    NodeType.HUMAN, Paradigm.HUMAN_IN_LOOP,
                    NodeType.PARALLEL, Paradigm.PARALLEL_RETRIEVAL,
                    NodeType.CONDITION, Paradigm.ROUTER,
                    NodeType.TOOL, Paradigm.TOOL_CALL);

    /**
     * 按节点类型推断范式分组。
     *
     * @param workflow 工作流定义
     * @return 只读建议列表
     */
    public List<RegionSuggestion> suggest(WorkflowDefinition workflow) {
        List<RegionSuggestion> suggestions = new ArrayList<>();
        if (workflow == null) {
            return suggestions;
        }
        Map<Paradigm, List<String>> grouped = new EnumMap<>(Paradigm.class);
        for (NodeDefinition node : workflow.nodes()) {
            Paradigm paradigm = TYPE_MAPPING.get(node.type());
            if (paradigm != null) {
                grouped.computeIfAbsent(paradigm, ignored -> new ArrayList<>()).add(node.id());
            }
        }
        grouped.forEach((paradigm, nodeIds) -> suggestions.add(new RegionSuggestion(
                "suggested:" + paradigm.name().toLowerCase(java.util.Locale.ROOT),
                paradigm, nodeIds, "按节点类型映射推断", 0.7)));
        suggestions.addAll(cyclicSuggestions(workflow));
        return List.copyOf(suggestions);
    }

    /**
     * 检测回边并给出 REFLECTION 建议。
     *
     * @param workflow 工作流
     * @return 建议列表
     */
    private List<RegionSuggestion> cyclicSuggestions(WorkflowDefinition workflow) {
        List<RegionSuggestion> suggestions = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Edge edge : workflow.edges()) {
            if (!workflow.canReach(edge.to(), edge.from())) {
                continue;
            }
            List<String> cycle = shortestPath(workflow, edge.to(), edge.from());
            if (cycle.isEmpty()) {
                continue;
            }
            List<String> nodeIds = new ArrayList<>();
            nodeIds.add(edge.from());
            nodeIds.addAll(cycle);
            List<String> unique = nodeIds.stream().distinct().toList();
            String key = String.join(",", unique.stream().sorted().toList());
            if (seen.add(key)) {
                suggestions.add(new RegionSuggestion("suggested:reflection", Paradigm.REFLECTION, unique,
                        "存在回边 " + edge.key() + "，可能是反思或迭代区域", 0.5));
            }
        }
        return suggestions;
    }

    /**
     * @param workflow 工作流
     * @param from     起点
     * @param to       终点
     * @return 最短路径上的节点（不含起点），无路径时为空
     */
    private List<String> shortestPath(WorkflowDefinition workflow, String from, String to) {
        Map<String, String> parent = new java.util.LinkedHashMap<>();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        queue.add(from);
        parent.put(from, null);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (current.equals(to)) {
                break;
            }
            for (Edge edge : workflow.outgoing(current)) {
                if (!parent.containsKey(edge.to())) {
                    parent.put(edge.to(), current);
                    queue.add(edge.to());
                }
            }
        }
        if (!parent.containsKey(to)) {
            return List.of();
        }
        List<String> path = new ArrayList<>();
        String current = to;
        while (current != null && !current.equals(from)) {
            path.add(0, current);
            current = parent.get(current);
        }
        return path;
    }
}
