package com.agentframework.engine.workflowruntime.executors;

import com.agentframework.definition.node.HumanNodeDefinition;
import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.node.NodeType;
import com.agentframework.engine.core.NodeContext;
import com.agentframework.engine.core.NodeResult;
import com.agentframework.engine.workflowruntime.NodeExecutor;
import com.agentframework.runtime.session.Message;
import java.util.Map;

/**
 * 人工节点执行器：有输入则继续，无输入则挂起。
 *
 * <p>挂起时运行时不推进游标，因此恢复执行时会重新进入同一节点。
 * 输入来源优先级：输入槽位 → payload 中的节点 id → payload 中的 human_input。</p>
 */
public final class HumanNodeExecutor implements NodeExecutor {

    @Override
    public NodeType type() {
        return NodeType.HUMAN;
    }

    @Override
    public NodeResult execute(NodeDefinition node, NodeContext context) {
        HumanNodeDefinition human = (HumanNodeDefinition) node;
        Object answer = resolveAnswer(human, context);
        if (answer != null) {
            if (!human.choices().isEmpty() && human.choices().stream().noneMatch(choice -> choice.equals(
                    String.valueOf(answer)))) {
                return NodeResult.failed(node.id(), "人工输入不在可选范围内：" + answer);
            }
            return NodeResult.completed(node.id(), String.valueOf(answer), Map.of(human.outputSlot(), answer))
                    .withMessage(Message.user(String.valueOf(answer)))
                    .withMetadata("humanNode", true);
        }
        return NodeResult.suspended(node.id(), renderPrompt(human, context));
    }

    /**
     * 从输入中解析人工答复。
     *
     * @param human   人工节点定义
     * @param context 节点上下文
     * @return 答复，未提供时返回 null
     */
    private Object resolveAnswer(HumanNodeDefinition human, NodeContext context) {
        if (context.input() == null) {
            return null;
        }
        Object fromSlots = context.input().slots().get(human.inputSlot());
        if (fromSlots != null) {
            return fromSlots;
        }
        Object fromNode = context.input().payload().get(human.id());
        if (fromNode != null) {
            return fromNode;
        }
        return context.input().payload().get("human_input");
    }

    /**
     * 渲染挂起提示语，支持 {@code {{变量}}} 占位。
     *
     * @param human   人工节点定义
     * @param context 节点上下文
     * @return 提示文本
     */
    private String renderPrompt(HumanNodeDefinition human, NodeContext context) {
        String template = human.promptTemplate();
        for (Map.Entry<String, Object> entry : context.slots().asMap().entrySet()) {
            template = template.replace("{{" + entry.getKey() + "}}", String.valueOf(entry.getValue()));
        }
        return template;
    }
}
