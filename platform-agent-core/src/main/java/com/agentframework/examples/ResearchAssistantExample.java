package com.agentframework.examples;

import com.agentframework.crosscutting.filter.Filters;
import com.agentframework.crosscutting.trace.SimpleTracer;
import com.agentframework.definition.agent.AgentDefinition;
import com.agentframework.definition.node.ConditionNodeDefinition;
import com.agentframework.definition.node.HumanNodeDefinition;
import com.agentframework.definition.node.LlmNodeDefinition;
import com.agentframework.definition.node.NodeMeta;
import com.agentframework.definition.node.ToolNodeDefinition;
import com.agentframework.definition.policy.CachePolicy;
import com.agentframework.definition.policy.QuotaPolicy;
import com.agentframework.definition.policy.TimeoutPolicy;
import com.agentframework.definition.policy.ToolPolicy;
import com.agentframework.definition.prompt.PromptDefinition;
import com.agentframework.definition.tool.ToolDefinition;
import com.agentframework.definition.tool.ToolParameter;
import com.agentframework.definition.tool.ToolPermission;
import com.agentframework.definition.tool.ToolSchema;
import com.agentframework.definition.workflow.SlotType;
import com.agentframework.definition.workflow.WorkflowBuilder;
import com.agentframework.definition.workflow.WorkflowDefinition;
import com.agentframework.engine.core.Engine;
import com.agentframework.engine.core.EngineBuilder;
import com.agentframework.engine.core.RunResult;
import com.agentframework.engine.toolexecutor.ToolResult;
import com.agentframework.infra.modelgateway.ModelResponse;
import com.agentframework.infra.modelgateway.ScriptedModelProvider;
import com.agentframework.infra.storage.InMemoryEventBus;
import com.agentframework.infra.storage.InMemorySpanExporter;
import com.agentframework.crosscutting.trace.RegionTraceGrouper;
import com.agentframework.definition.region.Paradigm;
import com.agentframework.definition.region.RegionDefinition;
import com.agentframework.runtime.event.Topics;
import com.agentframework.runtime.session.Input;
import com.agentframework.runtime.session.Session;
import com.agentframework.runtime.session.SessionState;
import com.agentframework.runtime.session.StartOptions;
import com.agentframework.sdk.Tools;
import java.time.Duration;
import java.util.List;

/**
 * 完整示例：包含 LLM、工具、条件路由、人工审批与报告的端到端执行链。
 *
 * <pre>
 * mvn -o -q compile
 * java -cp target/classes com.agentframework.examples.ResearchAssistantExample
 * </pre>
 *
 * <p>示例覆盖框架的关键能力：Prompt 作为资产、工具受策略约束、条件节点做路由、
 * 人工节点挂起会话、缓存与追踪贯穿执行链。</p>
 */
public final class ResearchAssistantExample {

    private ResearchAssistantExample() {
    }

    /**
     * 程序入口。
     *
     * @param args 未使用
     */
    public static void main(String[] args) {
        ScriptedModelProvider model = new ScriptedModelProvider("scripted")
                .enqueueText("研究计划：先估算样本量，再汇总结论", "研究报告：样本量为 6400，可以开始实验")
                .fallback(request -> ModelResponse.text("补充说明：" + request.lastUserMessage()));

        InMemoryEventBus events = new InMemoryEventBus();
        InMemorySpanExporter spans = new InMemorySpanExporter();

        Engine engine = EngineBuilder.create()
                .workflow(researchWorkflow())
                .prompt(PromptDefinition.template("plan-prompt",
                        "研究计划：请针对问题「{{slots.question}}」给出步骤。"))
                .prompt(PromptDefinition.template("report-prompt",
                        "研究报告：基于样本量「{{slots.sample_size}}」撰写结论。"))
                .prompt(PromptDefinition.template("publish-prompt", "对外发布前请润色：{{slots.approval}}"))
                .tool(ToolDefinition.of("sample-size",
                                ToolSchema.of("sample-size", "按置信水平估算样本量",
                                        ToolParameter.required("confidence", "number")))
                        .withPermission(ToolPermission.readOnly())
                        .withCache(CachePolicy.content(Duration.ofMinutes(5)))
                        .withTimeout(TimeoutPolicy.ofSeconds(5)),
                        Tools.of("sample-size", "按置信水平估算样本量",
                                input -> ToolResult.ok("样本量：" + (int) (1600
                                        * input.integer("confidence", 95) / 95.0))))
                .filter(new Filters.Pii())
                .modelProvider(model)
                .agent(AgentDefinition.builder("research-assistant", "1.0.0")
                        .workflow("research-workflow", "1.0.0")
                        .model("scripted", "demo-model")
                        .toolPolicy(ToolPolicy.only("sample-size"))
                        .quota(QuotaPolicy.of(100_000, 20, 10))
                        .build())
                .tracer(new SimpleTracer(true, null, List.of(spans)))
                .events(events)
                .build();

        events.subscribe(Topics.SESSION_SUSPENDED, event -> System.out.println("[事件] 会话挂起，等待人工确认"));
        events.subscribe(Topics.SESSION_COMPLETED, event -> System.out.println("[事件] 会话完成：" + event.payload()));

        Session session = engine.startSession(engine.loadAgent("research-assistant", "latest"),
                StartOptions.builder().user("alice").slot("question", "新药的样本量怎么定").build());

        RunResult first = engine.run(session, Input.of("请帮我估算样本量").withSlot("confidence", 95));
        System.out.println("第一次运行状态：" + first.state());
        System.out.println("挂起节点：" + first.suspendedNode());
        System.out.println("挂起提示：" + first.output());

        if (first.state() == SessionState.SUSPENDED) {
            RunResult resumed = engine.resume(session, Input.of("同意发布").withSlot("approval", "同意"));
            System.out.println("恢复后状态：" + resumed.state());
            System.out.println("最终输出：" + resumed.output());
            System.out.println("槽位快照：" + resumed.slots());
        }

        System.out.println("追踪 span：");
        spans.spans().forEach(span -> System.out.println("  - [" + span.kind() + "] " + span.name()
                + " " + span.duration().toMillis() + "ms"));
        System.out.println("按 Region 分组的追踪：");
        RegionTraceGrouper grouper = new RegionTraceGrouper();
        System.out.println(grouper.render(grouper.group("agent:research-assistant", spans.spans())));
        System.out.println("节点执行计数：" + engine.metrics().snapshot().stream()
                .filter(sample -> sample.name().equals("node.calls")).count());
        engine.close();
    }

    /**
     * @return 研究助手工作流：计划 → 工具 → 条件 → 人工 → 发布
     */
    private static WorkflowDefinition researchWorkflow() {
        return WorkflowBuilder.create("research-workflow", "1.0.0")
                .node(LlmNodeDefinition.of("plan", "plan-prompt", "plan"))
                .node(ToolNodeDefinition.of("estimate", "sample-size", "sample_size")
                        .withArgument("confidence", "${slots.confidence}"))
                .node(ConditionNodeDefinition.of("decide",
                        new ConditionNodeDefinition.Branch("approve", "slots.sample_size contains '样本量'"),
                        ConditionNodeDefinition.Branch.otherwise("plan")))
                .node(new HumanNodeDefinition("approve", "请确认样本量结果：{{sample_size}}",
                        "approval", "approval", null, null, null))
                .node(LlmNodeDefinition.of("publish", "publish-prompt", "report")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .edge("plan", "estimate")
                .edge("estimate", "decide")
                .edge("approve", "publish")
                .requiredSlot("question", SlotType.STRING)
                .slot("confidence", SlotType.NUMBER)
                .slot("plan", SlotType.STRING)
                .slot("sample_size", SlotType.STRING)
                .slot("approval", SlotType.STRING)
                .slot("report", SlotType.STRING)
                .region(RegionDefinition.of("r1_planning", Paradigm.ROUTER, "decide"))
                .region(RegionDefinition.of("r2_human_loop", Paradigm.HUMAN_IN_LOOP, "approve"))
                .cache(CachePolicy.content(Duration.ofMinutes(5)))
                .build();
    }
}
