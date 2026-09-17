package com.jjx.customer.platform.agent.framework.node;

import com.jjx.customer.platform.agent.framework.agent.AgentRequest;
import com.jjx.customer.platform.agent.framework.node.NodeContext;
import com.jjx.customer.platform.agent.framework.result.ExecutionResult;
import com.jjx.customer.platform.agent.framework.result.OutcomeKind;
import com.jjx.customer.platform.agent.framework.node.NodeKind;
import com.jjx.customer.platform.agent.framework.workflow.WorkflowStageSpec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 子 Agent 调用节点：按声明目标调用子 Agent（重入引擎内核）。
 *
 * <p>语义（设计 §4）：预算切分与截止经子调用上下文传给引擎（账本共享，子消耗上卷父账）；
 * 子执行轨迹作为嵌套步骤上卷；子产出按 outputMapping 回写父槽位。
 * 子失败不自动冒泡——以异常形式抛出，由父阶段的 {@code errorPolicy} 决定处置。</p>
 */
public class AgentCallNodeExecutor implements NodeExecutor {

    @Override
    public NodeKind nodeKind() {
        return NodeKind.AGENT_CALL;
    }

    @Override
    public NodeResult execute(NodeContext context) {
        WorkflowStageSpec stage = context.stage();
        String target = stage.targetAgentId();
        if (target == null || target.isBlank()) {
            throw new IllegalStateException("AGENT_CALL 阶段缺少目标 Agent: " + stage.name());
        }
        AgentRequest subRequest = mapInput(context, stage);

        ExecutionResult sub = context.agentInvoker().invoke(target, subRequest,
                context.metadataSink(), context.invocation(), context.budget(), stage.budgetShare());

        if (sub.kind() == OutcomeKind.ESCALATE) {
            throw new IllegalStateException("子 Agent 升级(" + target + "): " + sub.text());
        }
        // 计数填 0 是刻意的：子执行的 LLM/工具消耗经共享账本逐笔上卷父账
        // （ExecutionTrace 父子链），这里再报一次会双计。子自身消耗见嵌套子轨迹。
        return NodeResult.ok(sub.text(), 0, 0,
                sub.context().artifacts(),
                sub.trace() == null ? java.util.List.of() : sub.trace().steps(),
                mapOutput(stage, sub));
    }

    /** 输入映射：null/空 = 默认（原文 input + prefill + 槽位全传）；声明了则按映射取（父键 "input" = 用户原文）。 */
    private static AgentRequest mapInput(NodeContext context, WorkflowStageSpec stage) {
        if (stage.inputMapping().isEmpty()) {
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("input", context.request().input());
            attributes.putAll(context.plan().prefill());
            attributes.putAll(context.slots());
            return new AgentRequest(context.request().input(), attributes);
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        Map<String, Object> sources = new LinkedHashMap<>(context.plan().prefill());
        sources.putAll(context.slots());
        for (Map.Entry<String, String> e : stage.inputMapping().entrySet()) {
            Object value = "input".equals(e.getKey())
                    ? context.request().input() : sources.get(e.getKey());
            if (value != null) {
                attributes.put(e.getValue(), value);
            }
        }
        return new AgentRequest(context.request().input(), attributes);
    }

    /** 输出映射：子产出键 "text"（子结论文本）→ 父槽位键；未映射的产出不上写。 */
    private static Map<String, Object> mapOutput(WorkflowStageSpec stage, ExecutionResult sub) {
        Map<String, Object> updates = new LinkedHashMap<>();
        if (!stage.outputMapping().isEmpty() && sub.text() != null && !sub.text().isBlank()) {
            String slotKey = stage.outputMapping().get("text");
            if (slotKey != null && !slotKey.isBlank()) {
                updates.put(slotKey, sub.text());
            }
        }
        return updates;
    }
}
