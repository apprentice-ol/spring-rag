package com.agentframework.engine;

import com.agentframework.definition.agent.AgentDefinition;
import com.agentframework.definition.node.ConditionNodeDefinition;
import com.agentframework.definition.node.LlmNodeDefinition;
import com.agentframework.definition.node.NodeMeta;
import com.agentframework.definition.node.ToolNodeDefinition;
import com.agentframework.definition.policy.CachePolicy;
import com.agentframework.definition.policy.QuotaPolicy;
import com.agentframework.definition.policy.ToolPolicy;
import com.agentframework.definition.prompt.PromptDefinition;
import com.agentframework.definition.tool.ToolParameter;
import com.agentframework.definition.tool.ToolSchema;
import com.agentframework.definition.workflow.SlotType;
import com.agentframework.definition.workflow.WorkflowBuilder;
import com.agentframework.definition.workflow.WorkflowDefinition;
import com.agentframework.engine.toolexecutor.Tool;
import com.agentframework.engine.toolexecutor.ToolResult;
import com.agentframework.sdk.Tools;
import java.time.Duration;

/**
 * 引擎测试夹具：定义一条“计划 → 计算 → 判定 → 报告”的完整执行链。
 */
final class EngineFixture {

    static final String AGENT_ID = "assistant";

    private EngineFixture() {
    }

    /** @return 主测试工作流 */
    static WorkflowDefinition pipeline() {
        return WorkflowBuilder.create("pipeline", "1.0.0")
                .node(LlmNodeDefinition.of("plan", "plan-prompt", "plan"))
                .node(ToolNodeDefinition.of("calc", "calculator", "calc_result")
                        .withArgument("a", "${slots.a}")
                        .withArgument("b", "${slots.b}"))
                .node(ConditionNodeDefinition.of("decide",
                        new ConditionNodeDefinition.Branch("report", "slots.calc_result contains '结果'"),
                        ConditionNodeDefinition.Branch.otherwise("plan")))
                .node(LlmNodeDefinition.of("report", "report-prompt", "report")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .edge("plan", "calc")
                .edge("calc", "decide")
                .requiredSlot("question", SlotType.STRING)
                .requiredSlot("a", SlotType.NUMBER)
                .requiredSlot("b", SlotType.NUMBER)
                .slot("plan", SlotType.STRING)
                .slot("calc_result", SlotType.STRING)
                .slot("report", SlotType.STRING)
                .cache(CachePolicy.content(Duration.ofMinutes(5)))
                .build();
    }

    /** @return 计划节点的提示词资产 */
    static PromptDefinition planPrompt() {
        return PromptDefinition.template("plan-prompt", "计划：请分析问题「{{slots.question}}」并给出步骤。");
    }

    /** @return 报告节点的提示词资产 */
    static PromptDefinition reportPrompt() {
        return PromptDefinition.template("report-prompt", "报告：根据计算结果「{{slots.calc_result}}」撰写结论。");
    }

    /** @return 计算器工具 */
    static Tool calculator() {
        return Tools.of("calculator", "计算两个数字之和",
                ToolSchema.of("calculator", "加法",
                        ToolParameter.required("a", "number"),
                        ToolParameter.required("b", "number")),
                input -> ToolResult.ok("结果：" + (input.integer("a", 0) + input.integer("b", 0))));
    }

    /** @return Agent 定义 */
    static AgentDefinition agent() {
        return agent(ToolPolicy.allowAll(), QuotaPolicy.unlimited());
    }

    /**
     * @param toolPolicy 工具策略
     * @param quota      配额策略
     * @return Agent 定义
     */
    static AgentDefinition agent(ToolPolicy toolPolicy, QuotaPolicy quota) {
        return AgentDefinition.builder(AGENT_ID, "1.0.0")
                .workflow("pipeline", "1.0.0")
                .model("scripted", "test-model")
                .toolPolicy(toolPolicy)
                .quota(quota)
                .build();
    }
}
