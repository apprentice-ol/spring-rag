package com.agentframework.definition.view;

import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.node.NodeMeta;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点视图：前端画图与表单所需的全部字段。
 *
 * @param id         节点 id
 * @param type       节点类型
 * @param label      可读标签（Prompt / 工具 / 子工作流等引用）
 * @param outputSlot 输出槽位，可为 null
 * @param guards     节点级守卫引用
 * @param filters    节点级过滤器引用
 * @param regionId   所属 Region，可为 null
 * @param attributes 自定义属性
 */
public record NodeView(
        String id,
        String type,
        String label,
        String outputSlot,
        List<String> guards,
        List<String> filters,
        String regionId,
        Map<String, Object> attributes) {

    public NodeView {
        guards = List.copyOf(guards == null ? List.of() : guards);
        filters = List.copyOf(filters == null ? List.of() : filters);
        attributes = attributes == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    /** @return 可序列化文档 */
    public Map<String, Object> toDocument() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", id);
        document.put("type", type);
        document.put("label", label);
        document.put("outputSlot", outputSlot);
        document.put("guards", guards);
        document.put("filters", filters);
        document.put("regionId", regionId);
        document.put("attributes", attributes);
        return document;
    }

    /**
     * @param node     节点定义
     * @param regionId 所属区域
     * @return 视图
     */
    public static NodeView of(NodeDefinition node, String regionId) {
        NodeMeta meta = node.meta();
        return new NodeView(node.id(), node.type().name(), labelOf(node),
                com.agentframework.definition.workflow.WorkflowDefinition.outputSlotOf(node),
                meta == null ? List.of() : meta.guardRefs(),
                meta == null ? List.of() : meta.filterRefs(),
                regionId,
                meta == null ? Map.of() : meta.attributes());
    }

    /**
     * @param node 节点定义
     * @return 可读标签
     */
    private static String labelOf(NodeDefinition node) {
        return switch (node) {
            case com.agentframework.definition.node.LlmNodeDefinition llm -> llm.promptRef();
            case com.agentframework.definition.node.ToolNodeDefinition tool -> tool.toolRef();
            case com.agentframework.definition.node.SubWorkflowNodeDefinition sub -> sub.workflowId();
            case com.agentframework.definition.node.CustomNodeDefinition custom -> custom.executorRef();
            case com.agentframework.definition.node.ConditionNodeDefinition condition ->
                    condition.branches().size() + " branches";
            case com.agentframework.definition.node.ParallelNodeDefinition parallel ->
                    parallel.branches().size() + " branches";
            case com.agentframework.definition.node.HumanNodeDefinition human -> human.inputSlot();
            default -> node.type().name();
        };
    }
}
