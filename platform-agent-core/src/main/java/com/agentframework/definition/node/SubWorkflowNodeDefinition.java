package com.agentframework.definition.node;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 子工作流节点：执行另一个 Workflow，并把其结果写入父工作流的槽位。
 *
 * @param id              节点 id
 * @param workflowId      被调用的 Workflow id
 * @param workflowVersion 被调用的 Workflow 版本
 * @param inputMapping    父工作流槽位到子工作流槽位的映射
 * @param outputSlot      子工作流结果写入的父槽位名
 * @param meta            横切信息
 */
public record SubWorkflowNodeDefinition(
        String id,
        String workflowId,
        String workflowVersion,
        Map<String, String> inputMapping,
        String outputSlot,
        NodeMeta meta) implements NodeDefinition {

    public SubWorkflowNodeDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("sub-workflow node id is required");
        }
        if (workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException("sub-workflow node '" + id + "' requires a workflowId");
        }
        workflowVersion = workflowVersion == null ? "latest" : workflowVersion;
        inputMapping = inputMapping == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(inputMapping));
        outputSlot = outputSlot == null || outputSlot.isBlank() ? "sub_workflow_result" : outputSlot;
        meta = meta == null ? NodeMeta.empty() : meta;
    }

    /**
     * @param id         节点 id
     * @param workflowId 被调用的 Workflow id
     * @param outputSlot 输出槽位名
     * @return 子工作流节点定义
     */
    public static SubWorkflowNodeDefinition of(String id, String workflowId, String outputSlot) {
        return new SubWorkflowNodeDefinition(id, workflowId, null, null, outputSlot, null);
    }

    @Override
    public NodeType type() {
        return NodeType.SUB_WORKFLOW;
    }

    /**
     * @param subWorkflowSlot      子工作流中的槽位名
     * @param parentSlotExpression 父工作流中的取值表达式
     * @return 追加映射后的节点定义
     */
    public SubWorkflowNodeDefinition withInput(String subWorkflowSlot, String parentSlotExpression) {
        Map<String, String> merged = new LinkedHashMap<>(inputMapping);
        merged.put(subWorkflowSlot, parentSlotExpression);
        return new SubWorkflowNodeDefinition(id, workflowId, workflowVersion, merged, outputSlot, meta);
    }
}
