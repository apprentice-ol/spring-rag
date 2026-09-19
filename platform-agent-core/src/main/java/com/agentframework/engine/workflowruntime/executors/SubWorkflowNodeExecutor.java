package com.agentframework.engine.workflowruntime.executors;

import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.node.NodeType;
import com.agentframework.definition.node.SubWorkflowNodeDefinition;
import com.agentframework.definition.workflow.Edge;
import com.agentframework.definition.workflow.Expression;
import com.agentframework.definition.workflow.WorkflowDefinition;
import com.agentframework.engine.agentmanager.DefinitionSource;
import com.agentframework.engine.core.BranchInvoker;
import com.agentframework.engine.core.EngineConfig;
import com.agentframework.engine.core.NodeContext;
import com.agentframework.engine.core.NodeResult;
import com.agentframework.engine.workflowruntime.NodeExecutor;
import com.agentframework.runtime.session.Cursor;
import com.agentframework.runtime.slot.Slots;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 子工作流节点执行器：在同一会话内运行另一个工作流，并把结果写回父工作流槽位。
 *
 * <p>子工作流使用独立槽位容器（通过 {@code inputMapping} 注入入参），因此父子状态互不污染；
 * 节点执行复用运行时的 {@link BranchInvoker}，横切能力保持一致。</p>
 */
public final class SubWorkflowNodeExecutor implements NodeExecutor {

    private final DefinitionSource definitionSource;
    private final EngineConfig config;

    /**
     * @param definitionSource 定义来源，用于解析子工作流
     * @param config           引擎配置，可为 null
     */
    public SubWorkflowNodeExecutor(DefinitionSource definitionSource, EngineConfig config) {
        this.definitionSource = definitionSource;
        this.config = config == null ? EngineConfig.defaults() : config;
    }

    @Override
    public NodeType type() {
        return NodeType.SUB_WORKFLOW;
    }

    @Override
    public NodeResult execute(NodeDefinition node, NodeContext context) {
        SubWorkflowNodeDefinition sub = (SubWorkflowNodeDefinition) node;
        WorkflowDefinition child = definitionSource.workflow(sub.workflowId(), sub.workflowVersion())
                .orElse(null);
        if (child == null) {
            return NodeResult.failed(node.id(), "子工作流不存在：" + sub.workflowId());
        }
        BranchInvoker invoker = context.branchInvoker();
        if (invoker == null) {
            return NodeResult.failed(node.id(), "子工作流节点缺少节点调用入口");
        }
        Slots childSlots = new Slots();
        Map<String, Object> variables = context.variables();
        sub.inputMapping().forEach((childSlot, expression) ->
                childSlots.put(childSlot, Expression.value(expression, variables)));

        Cursor cursor = Cursor.at(child.entryNode().id());
        String output = "";
        int steps = 0;
        while (cursor.nodeId() != null && steps++ < config.maxSteps()) {
            NodeDefinition childNode = child.node(cursor.nodeId()).orElse(null);
            if (childNode == null) {
                return NodeResult.failed(node.id(), "子工作流游标指向不存在的节点：" + cursor.nodeId());
            }
            NodeContext childContext = context.withWorkflow(child, childSlots).withCursor(cursor);
            NodeResult result = invoker.invokeNode(childNode, childContext);
            if (!result.slotWrites().isEmpty()) {
                childSlots.putAll(result.slotWrites());
            }
            if (result.isFailed()) {
                return NodeResult.failed(node.id(), "子工作流节点失败：" + result.error());
            }
            if (result.isSuspended()) {
                return NodeResult.failed(node.id(), "子工作流暂不支持人工挂起：" + childNode.id());
            }
            output = result.output();
            String next = result.dynamicNextNodeId() != null
                    && child.dynamicPolicy().allows(childNode.id(), result.dynamicNextNodeId())
                            ? result.dynamicNextNodeId()
                            : result.nextNodeId() != null
                                    ? result.nextNodeId()
                                    : nextNode(child, childNode, childSlots);
            cursor = next == null ? Cursor.initial() : cursor.advanceTo(next);
        }
        Map<String, Object> writes = new LinkedHashMap<>();
        writes.put(sub.outputSlot(), output);
        writes.put(sub.outputSlot() + WorkflowDefinition.SLOTS_SLOT_SUFFIX, childSlots.asMap());
        return NodeResult.completed(node.id(), output, writes)
                .withMetadata("subWorkflow", child.key());
    }

    /**
     * 计算子工作流内的下一个节点。
     *
     * @param workflow 子工作流
     * @param node     当前节点
     * @param slots    子槽位
     * @return 下一个节点 id，null 表示结束
     */
    private String nextNode(WorkflowDefinition workflow, NodeDefinition node, Slots slots) {
        if (node.isTerminal()) {
            return null;
        }
        Map<String, Object> variables = Map.of("slots", slots.asMap(), "slot", slots.asMap());
        for (Edge edge : workflow.outgoing(node.id())) {
            if (edge.isConditional() && !Expression.evaluate(edge.condition(), variables)) {
                continue;
            }
            return edge.to();
        }
        return null;
    }
}
