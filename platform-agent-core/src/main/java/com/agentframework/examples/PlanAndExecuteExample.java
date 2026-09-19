package com.agentframework.examples;

import com.agentframework.crosscutting.metrics.InMemoryMetrics;
import com.agentframework.crosscutting.trace.SimpleTracer;
import com.agentframework.definition.agent.AgentDefinition;
import com.agentframework.definition.codec.JsonSupport;
import com.agentframework.definition.node.CustomNodeDefinition;
import com.agentframework.definition.node.LlmNodeDefinition;
import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.node.NodeMeta;
import com.agentframework.definition.node.NodeType;
import com.agentframework.definition.node.ToolNodeDefinition;
import com.agentframework.definition.policy.QuotaPolicy;
import com.agentframework.definition.policy.TimeoutPolicy;
import com.agentframework.definition.policy.ToolPolicy;
import com.agentframework.definition.prompt.PromptDefinition;
import com.agentframework.definition.region.Paradigm;
import com.agentframework.definition.region.RegionDefinition;
import com.agentframework.definition.region.RegionLoop;
import com.agentframework.definition.region.RegionPolicy;
import com.agentframework.definition.tool.ToolDefinition;
import com.agentframework.definition.tool.ToolParameter;
import com.agentframework.definition.tool.ToolPermission;
import com.agentframework.definition.tool.ToolSchema;
import com.agentframework.definition.workflow.SlotType;
import com.agentframework.definition.workflow.WorkflowBuilder;
import com.agentframework.definition.workflow.WorkflowDefinition;
import com.agentframework.engine.core.Engine;
import com.agentframework.engine.core.EngineBuilder;
import com.agentframework.engine.core.NodeContext;
import com.agentframework.engine.core.NodeResult;
import com.agentframework.engine.core.RunResult;
import com.agentframework.engine.policy.RegionMetrics;
import com.agentframework.engine.toolexecutor.DefaultToolRegistry;
import com.agentframework.engine.toolexecutor.ToolResult;
import com.agentframework.engine.workflowruntime.NodeExecutor;
import com.agentframework.infra.modelgateway.ModelResponse;
import com.agentframework.infra.modelgateway.ScriptedModelProvider;
import com.agentframework.infra.storage.InMemoryEventBus;
import com.agentframework.infra.storage.InMemorySpanExporter;
import com.agentframework.runtime.event.Topics;
import com.agentframework.runtime.session.Input;
import com.agentframework.runtime.session.Session;
import com.agentframework.runtime.session.SessionState;
import com.agentframework.runtime.session.StartOptions;
import com.agentframework.runtime.slot.Slots;
import com.agentframework.sdk.Tools;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plan-and-Execute 范式示例：先出计划，再逐步执行，最后汇总回答。
 *
 * <p>核心设计：<b>计划是数据，不是拓扑</b>。计划以 JSON 存在槽位里，步骤种类由
 * {@code step_pick} 节点通过动态边派发到对应的执行节点；循环由 Region 治理。</p>
 *
 * <pre>
 * plan(LLM) → step_pick(CUSTOM) ─动态边─▶ step_search(TOOL) / step_llm(LLM) / answer(LLM)
 *                    ▲                                   │
 *                    └──────── step_record(CUSTOM) ◀─────┘
 * </pre>
 *
 * <pre>
 * mvn -o -q compile
 * java -cp agent-framework-core/target/classes com.agentframework.examples.PlanAndExecuteExample
 * </pre>
 */
public final class PlanAndExecuteExample {

    /** Planner 提示词：只输出 JSON 计划，便于程序解析。 */
    private static final String PLAN_PROMPT = """
            你是任务规划器。把用户任务拆成 2~4 个可执行步骤，只输出 JSON 数组，不要解释：
            [{"id":"s1","kind":"search","input":"..."},{"id":"s2","kind":"llm","input":"..."}]
            kind 只能是：search（查外部事实）、llm（基于已有结果推理）。
            用户任务：{{input}}
            """;

    /** 执行类步骤提示词。 */
    private static final String STEP_PROMPT = """
            你是执行器。根据当前步骤与已有结果给出结论，只输出结论本身。
            当前步骤：{{slots.step_input}}
            已有结果：{{slots.step_results}}
            """;

    /** 汇总提示词。 */
    private static final String ANSWER_PROMPT = """
            根据下面的步骤结果给出简洁的中文最终回答，只输出答案本身：
            {{slots.step_results}}
            """;

    private PlanAndExecuteExample() {
    }

    /**
     * 程序入口。
     *
     * @param args 未使用
     */
    public static void main(String[] args) {
        // ① 工具（步骤执行复用内核的 TOOL 节点，治理链路完整）
        DefaultToolRegistry tools = new DefaultToolRegistry();
        ToolSchema searchSchema = ToolSchema.of("web-search", "查询外部事实（示例为模拟数据）",
                ToolParameter.required("query", "string"));
        tools.register(ToolDefinition.of("web-search", searchSchema)
                        .withPermission(ToolPermission.readOnly().withNetwork())
                        .withTimeout(TimeoutPolicy.ofSeconds(3)),
                Tools.of("web-search", "查询外部事实（示例为模拟数据）", searchSchema,
                        input -> ToolResult.ok(
                                "1 USD = 7.12 CNY（query=" + input.string("query", "") + "）")));

        // ② 观测设施
        InMemoryEventBus events = new InMemoryEventBus();
        InMemorySpanExporter spans = new InMemorySpanExporter();
        InMemoryMetrics metrics = new InMemoryMetrics();
        SimpleTracer tracer = new SimpleTracer(true, null, List.of(spans));

        // ③ 脚本化模型：计划 → 单步推理 → 最终回答
        ScriptedModelProvider model = new ScriptedModelProvider("scripted")
                .enqueueText("""
                        [{"id":"s1","kind":"search","input":"查询 USD 对 CNY 汇率"},
                         {"id":"s2","kind":"llm","input":"按汇率换算 100 美元"}]""")
                .enqueueText("按 7.12 的汇率，100 美元约合 712 元人民币。")
                .enqueueText("100 美元约合 712 元人民币（1 USD = 7.12 CNY）。")
                .fallback(request -> ModelResponse.text("脚本耗尽，兜底回答"));

        // ④ 装配
        Engine engine = EngineBuilder.create()
                .workflow(planAndExecuteWorkflow())
                .prompt(PromptDefinition.template("plan-prompt", PLAN_PROMPT))
                .prompt(PromptDefinition.template("step-prompt", STEP_PROMPT))
                .prompt(PromptDefinition.template("answer-prompt", ANSWER_PROMPT))
                .toolRegistry(tools)
                .modelProvider(model)
                .defaultModelProvider("scripted")
                .nodeExecutor("plan-step-pick", new PlanStepPickExecutor())
                .nodeExecutor("plan-record", new PlanRecordExecutor())
                .events(events)
                .tracer(tracer)
                .metrics(metrics)
                .loopGuard("plan-loop", 8)
                .agent(AgentDefinition.builder("plan-exec-agent", "1.0.0")
                        .workflow("plan-exec-agent", "1.0.0")
                        .model("scripted", "demo-model")
                        .toolPolicy(ToolPolicy.only("web-search"))
                        .quota(QuotaPolicy.of(100_000, 30, 10))
                        .build())
                .build();

        // ⑤ 事件订阅
        List<String> observed = new ArrayList<>();
        events.subscribe(Topics.ROUTE_DYNAMIC, event -> observed.add("route.dynamic:" + event.payload()));
        events.subscribe(Topics.ROUTE_REJECTED, event -> observed.add("route.rejected:" + event.payload()));
        events.subscribe(Topics.LOOP_BREAK, event -> observed.add("loop.break:" + event.payload().get("reason")));

        try {
            // ⑥ 运行
            Session session = engine.startSession(engine.loadAgent("plan-exec-agent", "latest"),
                    StartOptions.builder().user("demo").build());
            RunResult result = engine.run(session, Input.of("100 美元能换多少人民币？"));

            // ⑦ 打印
            System.out.println("== Plan-and-Execute 执行结果 ==");
            System.out.println("状态    : " + result.state());
            System.out.println("输出    : " + result.output());
            System.out.println("执行路径: " + result.visitedNodes());
            System.out.println("计划    : " + result.slots().get("plan"));
            System.out.println("步骤结果:\n" + result.slots().get("step_results"));
            System.out.println("事件    : " + observed);
            System.out.println("span    : " + spans.spans().stream()
                    .map(span -> span.kind() + ":" + span.name())
                    .toList());
            printRegionMetrics(engine.regionMetrics(session.id()));

            // ⑧ 断言
            check(result.state() == SessionState.COMPLETED, "会话应完成，实际：" + result.state()
                    + "，错误：" + result.error());
            check(result.visitedNodes().equals(List.of(
                    "plan",
                    "step_pick", "step_search", "step_record",
                    "step_pick", "step_llm", "step_record",
                    "step_pick", "answer")), "执行路径不符合预期：" + result.visitedNodes());
            check(result.output().contains("712"), "最终回答应包含换算结果，实际：" + result.output());
            String stepResults = String.valueOf(result.slots().get("step_results"));
            check(stepResults.contains("[s1/search]") && stepResults.contains("[s2/llm]"),
                    "步骤结果应记录两类步骤，实际：" + stepResults);
            check(Integer.valueOf(3).equals(asInt(result.slots().get("iteration"))),
                    "Region 循环应迭代 3 次，实际：" + result.slots().get("iteration"));
            check(events.count(Topics.ROUTE_DYNAMIC) == 3L,
                    "应有 3 次动态选路，实际：" + events.count(Topics.ROUTE_DYNAMIC));
            check(events.count(Topics.ROUTE_REJECTED) == 0L, "正常路径不应出现 route.rejected");
            check(events.count(Topics.LOOP_BREAK) == 0L, "正常路径不应触发 loop.break");
            check(metrics.counterValue("model.calls") == 3L,
                    "应有 3 次模型调用（plan / step_llm / answer），实际：" + metrics.counterValue("model.calls"));
            System.out.println("\n全部断言通过 ✅");
        } finally {
            engine.close();
        }
    }

    /**
     * @return Plan-and-Execute 工作流：计划 → 逐步执行 → 汇总
     */
    private static WorkflowDefinition planAndExecuteWorkflow() {
        return WorkflowBuilder.create("plan-exec-agent", "1.0.0")
                .node(LlmNodeDefinition.of("plan", "plan-prompt", "plan"))
                .node(CustomNodeDefinition.of("step_pick", "plan-step-pick", "step_pick_out"))
                .node(ToolNodeDefinition.of("step_search", "web-search", "step_output")
                        .withArgument("query", "${slots.step_input}"))
                .node(LlmNodeDefinition.of("step_llm", "step-prompt", "step_output"))
                .node(CustomNodeDefinition.of("step_record", "plan-record", "step_record_out"))
                .node(LlmNodeDefinition.of("answer", "answer-prompt", "answer")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .edge("plan", "step_pick")
                .edge("step_pick", "step_search")
                .edge("step_pick", "step_llm")
                .edge("step_pick", "answer")
                .edge("step_search", "step_record")
                .edge("step_llm", "step_record")
                .edge("step_record", "step_pick")
                .dynamic("step_pick", "step_search", "step_llm", "answer")
                .requiredSlot("question", SlotType.STRING)
                .slot("plan", SlotType.STRING)
                .slot("step_index", SlotType.NUMBER)
                .slot("step_id", SlotType.STRING)
                .slot("step_kind", SlotType.STRING)
                .slot("step_input", SlotType.STRING)
                .slot("step_output", SlotType.STRING)
                .slot("step_results", SlotType.STRING)
                .slot("iteration", SlotType.NUMBER)
                .slot("answer", SlotType.STRING)
                .region(RegionDefinition.of("r_exec", Paradigm.CUSTOM,
                                "step_pick", "step_search", "step_llm", "step_record")
                        .withLoop(RegionLoop.of("step_pick", "step_record", 8).withCounterSlot("iteration"))
                        .withPolicy(new RegionPolicy(RegionPolicy.bindings("plan-loop"), null, null,
                                QuotaPolicy.of(120_000, 40, 24))))
                .build();
    }

    /**
     * 步骤派发节点：解析计划 → 取下一个未执行步骤 → 用动态边选择执行节点。
     */
    private static final class PlanStepPickExecutor implements NodeExecutor {

        @Override
        public NodeType type() {
            return NodeType.CUSTOM;
        }

        @Override
        public NodeResult execute(NodeDefinition node, NodeContext context) {
            Slots slots = context.slots();
            List<Map<String, Object>> steps = parsePlan(slots.getString("plan", ""));
            int index = slots.getInt("step_index", 0);
            if (index >= steps.size()) {
                return NodeResult.dynamic(node.id(), "计划已执行完毕", "answer");
            }
            Map<String, Object> step = steps.get(index);
            String kind = String.valueOf(step.getOrDefault("kind", "llm"));
            String input = String.valueOf(step.getOrDefault("input", ""));
            String id = String.valueOf(step.getOrDefault("id", "s" + (index + 1)));
            return NodeResult.dynamic(node.id(), input, "step_" + kind)
                    .withSlotWrite("step_index", index + 1)
                    .withSlotWrite("step_id", id)
                    .withSlotWrite("step_kind", kind)
                    .withSlotWrite("step_input", input);
        }

        /**
         * @param raw 模型输出的计划文本（可能带 ```json 围栏）
         * @return 步骤列表，解析失败时返回空列表
         */
        @SuppressWarnings("unchecked")
        private List<Map<String, Object>> parsePlan(String raw) {
            String json = raw.replaceAll("(?s)```(?:json)?", "").trim();
            int start = json.indexOf('[');
            int end = json.lastIndexOf(']');
            if (start < 0 || end <= start) {
                return List.of();
            }
            Object parsed = JsonSupport.parse(json.substring(start, end + 1));
            if (!(parsed instanceof List<?> list)) {
                return List.of();
            }
            List<Map<String, Object>> steps = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    steps.add((Map<String, Object>) map);
                }
            }
            return steps;
        }
    }

    /**
     * 步骤归档节点：把本步结果追加进历史，供后续步骤与最终回答使用。
     */
    private static final class PlanRecordExecutor implements NodeExecutor {

        @Override
        public NodeType type() {
            return NodeType.CUSTOM;
        }

        @Override
        public NodeResult execute(NodeDefinition node, NodeContext context) {
            Slots slots = context.slots();
            String id = slots.getString("step_id", "?");
            String kind = slots.getString("step_kind", "?");
            String output = slots.getString("step_output", "");
            String history = slots.getString("step_results", "");
            String merged = history + "[" + id + "/" + kind + "] " + output + "\n";
            return NodeResult.completed(node.id(), output, Map.of("step_results", merged));
        }
    }

    /**
     * @param metrics 区域指标
     */
    private static void printRegionMetrics(Map<String, RegionMetrics> metrics) {
        System.out.println("Region 指标:");
        metrics.forEach((regionId, m) -> System.out.printf(
                "  - %s(%s) 节点=%d LLM=%d 工具=%d token=%d 迭代=%d 耗时=%dms%n",
                regionId, m.paradigm(), m.nodeCount(), m.llmCalls(), m.toolCalls(),
                m.tokenCount(), m.iterationCount(), m.executionTimeMs()));
    }

    /**
     * @param value 任意数值
     * @return 整数视图，非数值返回 null
     */
    private static Integer asInt(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    /**
     * @param condition 断言条件
     * @param message   失败信息
     */
    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("断言失败：" + message);
        }
    }
}
