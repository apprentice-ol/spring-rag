package com.agentframework.examples;

import com.agentframework.crosscutting.cache.ContentHashCacheKeyBuilder;
import com.agentframework.crosscutting.cache.InMemoryCacheStore;
import com.agentframework.crosscutting.interceptor.InterceptorAttributes;
import com.agentframework.crosscutting.interceptor.Interceptors;
import com.agentframework.crosscutting.metrics.InMemoryMetrics;
import com.agentframework.crosscutting.trace.SimpleTracer;
import com.agentframework.definition.agent.AgentDefinition;
import com.agentframework.definition.node.ConditionNodeDefinition;
import com.agentframework.definition.node.CustomNodeDefinition;
import com.agentframework.definition.node.LlmNodeDefinition;
import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.node.NodeMeta;
import com.agentframework.definition.node.NodeType;
import com.agentframework.definition.policy.CachePolicy;
import com.agentframework.definition.policy.QuotaPolicy;
import com.agentframework.definition.policy.RetryPolicy;
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
import com.agentframework.engine.policy.PolicyAttributes;
import com.agentframework.engine.policy.RegionMetrics;
import com.agentframework.engine.toolexecutor.DefaultToolExecutor;
import com.agentframework.engine.toolexecutor.DefaultToolRegistry;
import com.agentframework.engine.toolexecutor.ToolContext;
import com.agentframework.engine.toolexecutor.ToolExecutor;
import com.agentframework.engine.toolexecutor.ToolInvocation;
import com.agentframework.engine.toolexecutor.ToolRegistry;
import com.agentframework.engine.toolexecutor.ToolResult;
import com.agentframework.engine.workflowruntime.NodeExecutor;
import com.agentframework.extension.permission.QuotaEnforcer;
import com.agentframework.infra.modelgateway.ModelResponse;
import com.agentframework.infra.modelgateway.ScriptedModelProvider;
import com.agentframework.infra.modelgateway.ToolCall;
import com.agentframework.infra.storage.InMemoryEventBus;
import com.agentframework.infra.storage.InMemorySpanExporter;
import com.agentframework.engine.middleware.DefaultMiddlewarePipeline;
import com.agentframework.engine.middleware.MiddlewarePipeline;
import com.agentframework.runtime.event.Topics;
import com.agentframework.runtime.session.Input;
import com.agentframework.runtime.session.Message;
import com.agentframework.runtime.session.Session;
import com.agentframework.runtime.session.SessionState;
import com.agentframework.runtime.session.StartOptions;
import com.agentframework.runtime.slot.Slots;
import com.agentframework.sdk.Tools;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ReAct 范式示例：Thought → Action → Observation 循环 + Region 循环治理。
 *
 * <p>与内核的分工：</p>
 * <ul>
 *   <li>{@code LlmNodeExecutor} 负责 Think：渲染 Prompt、把允许的工具契约交给模型，
 *       并把模型返回的 {@code toolCalls} 写入 {@code <outputSlot>_tool_calls} 槽位。</li>
 *   <li>本示例的 {@link ReactActExecutor} 负责 Act：读取该槽位，逐个执行工具，
 *       把 Action / Observation 追加进 scratchpad 槽位。</li>
 *   <li>{@code RegionLoop} 负责循环身份与硬上限，{@code decide} 条件节点负责"是否继续"。</li>
 * </ul>
 *
 * <pre>
 * mvn -o -q compile
 * java -cp agent-framework-core/target/classes com.agentframework.examples.ReActExample
 * </pre>
 */
public final class ReActExample {

    /** ReAct 系统提示词：必须把过程记录回灌给模型（会话消息不带结构化 tool_calls）。 */
    private static final String REACT_PROMPT = """
            你是一个 ReAct 风格的助手：先思考，再决定是否调用工具，拿到观察后继续推理。
            可用工具：
            - web-search(query: string)：查询外部事实
            - calculator(a: number, b: number)：两数相乘

            规则：
            1. 需要外部事实或计算时必须调用工具，一次只调用一个。
            2. 已经能回答时不要再调用工具，直接给出结论。
            3. 下面是已经发生过的过程，不要重复执行：

            {{slots.react_scratchpad}}
            """;

    /** 收尾提示词：把过程记录交给模型组织成最终回答。 */
    private static final String ANSWER_PROMPT = """
            根据过程记录给出简洁的中文最终答案，只输出答案本身。
            过程记录：
            {{slots.react_scratchpad}}
            最后一次思考：{{slots.react_out}}
            """;

    private ReActExample() {
    }

    /**
     * 程序入口。
     *
     * @param args 未使用
     */
    public static void main(String[] args) {
        // ① 工具：注册表 + 定义（权限 / 缓存 / 超时 / 重试都在定义上声明）
        DefaultToolRegistry tools = new DefaultToolRegistry();
        registerTools(tools);

        // ② 观测与横切设施：示例里显式创建，便于断言与打印
        InMemoryEventBus events = new InMemoryEventBus();
        InMemorySpanExporter spans = new InMemorySpanExporter();
        InMemoryMetrics metrics = new InMemoryMetrics();
        InMemoryCacheStore cache = new InMemoryCacheStore();
        SimpleTracer tracer = new SimpleTracer(true, null, List.of(spans));
        QuotaEnforcer quota = new QuotaEnforcer();

        // ③ 自定义 Act 节点：引擎内部的 ToolExecutor 未对外暴露，因此自建一份同构管道
        MiddlewarePipeline toolPipeline = new DefaultMiddlewarePipeline()
                .register(new Interceptors.Trace(tracer))
                .register(new Interceptors.MetricsInterceptor(metrics, "tool"))
                .register(new Interceptors.Cache(cache, new ContentHashCacheKeyBuilder(), events, metrics));
        ToolExecutor toolExecutor = new DefaultToolExecutor(tools, toolPipeline, metrics, events, quota);

        // ④ 脚本化模型：两次工具调用 → 一次收尾 → 一次最终回答
        ScriptedModelProvider model = new ScriptedModelProvider("scripted")
                .enqueue(ModelResponse.tools(List.of(
                        ToolCall.of("web-search", Map.of("query", "USD CNY 汇率")))))
                .enqueue(ModelResponse.tools(List.of(
                        ToolCall.of("calculator", Map.of("a", 7.12, "b", 100)))))
                .enqueueText("已经拿到汇率与换算结果，可以给出结论。")
                .enqueueText("100 美元约合 712 元人民币（按 1 USD = 7.12 CNY 计算）。")
                .fallback(request -> ModelResponse.text("Final Answer: 脚本耗尽，兜底回答"));

        // ⑤ 装配引擎
        Engine engine = EngineBuilder.create()
                .workflow(reactWorkflow())
                .prompt(PromptDefinition.template("react-prompt", REACT_PROMPT))
                .prompt(PromptDefinition.template("answer-prompt", ANSWER_PROMPT))
                .toolRegistry(tools)
                .modelProvider(model)
                .defaultModelProvider("scripted")
                .nodeExecutor("react-act", new ReactActExecutor(tools, toolExecutor))
                .events(events)
                .tracer(tracer)
                .metrics(metrics)
                .cache(cache)
                .loopGuard("react-loop", 6)
                .agent(AgentDefinition.builder("react-agent", "1.0.0")
                        .workflow("react-agent", "1.0.0")
                        .model("scripted", "demo-model")
                        .toolPolicy(ToolPolicy.only("web-search", "calculator"))
                        .quota(QuotaPolicy.of(100_000, 20, 10))
                        .build())
                .build();

        // ⑥ 事件订阅：把关键事件打出来，同时用于断言
        List<String> observed = new java.util.ArrayList<>();
        events.subscribe(Topics.TOOL_INVOKED, event -> observed.add("tool.invoked:" + event.payload()));
        events.subscribe(Topics.LOOP_BREAK, event -> observed.add("loop.break:" + event.payload().get("reason")));
        events.subscribe(Topics.SESSION_COMPLETED, event -> observed.add("session.completed"));

        try {
            // ⑦ 运行
            Session session = engine.startSession(engine.loadAgent("react-agent", "latest"),
                    StartOptions.builder()
                            .user("demo")
                            .slot("react_scratchpad", "")
                            .build());
            RunResult result = engine.run(session, Input.of("100 美元现在值多少人民币？"));

            // ⑧ 打印：执行路径、槽位、事件、span、Region 指标
            System.out.println("== ReAct 执行结果 ==");
            System.out.println("状态      : " + result.state());
            System.out.println("输出      : " + result.output());
            System.out.println("执行路径  : " + result.visitedNodes());
            System.out.println("迭代次数  : " + result.slots().get("iteration"));
            System.out.println("scratchpad:\n" + result.slots().get("react_scratchpad"));
            System.out.println("事件      : " + observed);
            System.out.println("span      : " + spans.spans().stream()
                    .map(span -> span.kind() + ":" + span.name() + "(" + span.duration().toMillis() + "ms)")
                    .toList());
            printRegionMetrics(engine.regionMetrics(session.id()));

            // ⑨ 断言：把"这条 ReAct 链路真的按预期跑"锁住
            check(result.state() == SessionState.COMPLETED, "会话应完成，实际：" + result.state()
                    + "，错误：" + result.error());
            check(result.visitedNodes().equals(List.of(
                    "think", "act", "decide",
                    "think", "act", "decide",
                    "think", "act", "decide",
                    "answer")), "执行路径不符合预期：" + result.visitedNodes());
            check(result.output().contains("712"), "最终回答应包含换算结果，实际：" + result.output());
            check(String.valueOf(result.slots().get("react_scratchpad")).contains("Observation"),
                    "scratchpad 应包含观察记录");
            check(Integer.valueOf(3).equals(asInt(result.slots().get("iteration"))),
                    "Region 循环应迭代 3 次，实际：" + result.slots().get("iteration"));
            check(metrics.counterValue("tool.calls") == 2L,
                    "应执行 2 次工具调用，实际：" + metrics.counterValue("tool.calls"));
            check(events.count(Topics.TOOL_INVOKED) == 2L, "应有 2 个 tool.invoked 事件");
            check(events.count(Topics.LOOP_BREAK) == 0L, "正常路径不应触发 loop.break");
            System.out.println("\n全部断言通过 ✅");
        } finally {
            engine.close();
        }
    }

    /**
     * @return ReAct 工作流：think → act → decide（继续 / 收尾），循环由 Region 治理
     */
    private static WorkflowDefinition reactWorkflow() {
        return WorkflowBuilder.create("react-agent", "1.0.0")
                .node(LlmNodeDefinition.of("think", "react-prompt", "react_out"))
                .node(CustomNodeDefinition.of("act", "react-act", "react_observation"))
                .node(ConditionNodeDefinition.of("decide",
                        new ConditionNodeDefinition.Branch("think", "slots.react_has_calls > 0"),
                        ConditionNodeDefinition.Branch.otherwise("answer")))
                .node(LlmNodeDefinition.of("answer", "answer-prompt", "answer")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .edge("think", "act")
                .edge("act", "decide")
                .edge("decide", "think")
                .edge("decide", "answer")
                .requiredSlot("question", SlotType.STRING)
                .slot("react_out", SlotType.STRING)
                .slot("react_out_tool_calls", SlotType.ARRAY)
                .slot("react_scratchpad", SlotType.STRING)
                .slot("react_has_calls", SlotType.NUMBER)
                .slot("react_observation", SlotType.STRING)
                .slot("iteration", SlotType.NUMBER)
                .slot("answer", SlotType.STRING)
                .region(RegionDefinition.of("r_react", Paradigm.TOOL_CALL, "think", "act", "decide")
                        .withLoop(RegionLoop.of("think", "act", 6).withCounterSlot("iteration"))
                        .withPolicy(new RegionPolicy(RegionPolicy.bindings("react-loop"), null, null,
                                QuotaPolicy.of(100_000, 20, 10))))
                .build();
    }

    /**
     * @param registry 工具注册表
     */
    private static void registerTools(DefaultToolRegistry registry) {
        ToolSchema searchSchema = ToolSchema.of("web-search", "查询外部事实（示例为模拟数据）",
                ToolParameter.required("query", "string"));
        registry.register(ToolDefinition.of("web-search", searchSchema)
                        .withPermission(ToolPermission.readOnly().withNetwork())
                        .withCache(CachePolicy.content(Duration.ofMinutes(5)))
                        .withTimeout(TimeoutPolicy.ofSeconds(3))
                        .withRetry(RetryPolicy.of(2, Duration.ofMillis(50))),
                Tools.of("web-search", "查询外部事实（示例为模拟数据）", searchSchema,
                        input -> ToolResult.ok("1 USD = 7.12 CNY（query=" + input.string("query", "") + "）")));

        ToolSchema calcSchema = ToolSchema.of("calculator", "两数相乘",
                ToolParameter.required("a", "number"), ToolParameter.required("b", "number"));
        registry.register(ToolDefinition.of("calculator", calcSchema)
                        .withPermission(ToolPermission.readOnly())
                        .withTimeout(TimeoutPolicy.ofSeconds(1)),
                Tools.of("calculator", "两数相乘", calcSchema, input -> {
                    double a = asDouble(input.arguments().get("a"));
                    double b = asDouble(input.arguments().get("b"));
                    return ToolResult.ok(String.valueOf(a * b));
                }));
    }

    /**
     * Act 节点：执行模型选出的工具调用，并把过程追加进 scratchpad。
     *
     * <p>注意：这是一个 {@code CUSTOM} 节点，因此 Region 指标里的 {@code toolCalls} 不会计入它
     * 内部的工具调用；工具调用量请看 {@code tool.calls} 指标与 {@code tool.invoked} 事件。
     * 若希望指标也归到 Region，用"每个工具一个内置 TOOL 节点 + 条件派发"的写法。</p>
     */
    private static final class ReactActExecutor implements NodeExecutor {

        private final ToolRegistry registry;
        private final ToolExecutor toolExecutor;

        ReactActExecutor(ToolRegistry registry, ToolExecutor toolExecutor) {
            this.registry = registry;
            this.toolExecutor = toolExecutor;
        }

        @Override
        public NodeType type() {
            return NodeType.CUSTOM;
        }

        @Override
        public NodeResult execute(NodeDefinition node, NodeContext context) {
            Slots slots = context.slots();
            Object raw = slots.get("react_out_tool_calls");
            List<?> calls = raw instanceof List<?> list ? list : List.of();

            StringBuilder trace = new StringBuilder(slots.getString("react_scratchpad", ""));
            String thought = slots.getString("react_out", "");
            if (!thought.isBlank()) {
                trace.append("Thought: ").append(thought).append('\n');
            }

            int executed = 0;
            for (Object item : calls) {
                if (!(item instanceof ToolCall call)) {
                    continue;
                }
                ToolResult toolResult = run(call, node, context);
                executed++;
                trace.append("Action: ").append(call.name()).append(' ').append(call.arguments()).append('\n')
                        .append("Observation: ")
                        .append(toolResult.success() ? toolResult.output() : "调用失败：" + toolResult.error())
                        .append('\n');
            }
            trace.append("---\n");

            String text = trace.toString();
            Map<String, Object> writes = new LinkedHashMap<>();
            writes.put("react_scratchpad", text);
            writes.put("react_has_calls", executed > 0 ? 1 : 0);
            writes.put("react_observation", text);
            // 关键：消费后清空调用槽位，否则模型这一轮没给 tool_calls 时会把上一轮的调用再执行一次
            writes.put("react_out_tool_calls", List.of());
            return NodeResult.completed(node.id(), text, writes)
                    .withMessage(Message.tool("react", text.isEmpty() ? "(无工具调用)" : text));
        }

        /**
         * @param call    模型发起的工具调用
         * @param node    当前节点
         * @param context 节点上下文
         * @return 工具结果
         */
        private ToolResult run(ToolCall call, NodeDefinition node, NodeContext context) {
            return registry.resolve(call.name(), ToolDefinition.LATEST)
                    .map(tool -> {
                        Map<String, Object> attributes = new LinkedHashMap<>();
                        attributes.put("toolId", tool.id());
                        attributes.put(InterceptorAttributes.TRACE, context.trace());
                        attributes.put(InterceptorAttributes.TRACE_PARENT, context.parentSpan());
                        if (context.agent() != null) {
                            attributes.put(DefaultToolExecutor.TOOL_POLICY_ATTRIBUTE,
                                    context.agent().policies().tool());
                            attributes.put(DefaultToolExecutor.QUOTA_POLICY_ATTRIBUTE,
                                    context.agent().policies().quota());
                        }
                        Object resolvedPolicy = context.attributes().get(PolicyAttributes.RESOLVED);
                        if (resolvedPolicy != null) {
                            attributes.put(PolicyAttributes.RESOLVED, resolvedPolicy);
                        }
                        ToolContext toolContext = ToolContext.of(context.session().id(), node.id())
                                .withTraceId(context.session().traceId())
                                .withWorkspace(context.workspace())
                                .withSlots(context.slots())
                                .withAttributes(attributes);
                        return toolExecutor.execute(ToolInvocation.of(tool.id(), call.arguments())
                                .withVersion(ToolDefinition.LATEST)
                                .withOwner(context.session().id(), node.id()), toolContext);
                    })
                    .orElseGet(() -> ToolResult.failed("未注册工具：" + call.name()));
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
     * @return double 视图
     */
    private static double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue()
                : Double.parseDouble(String.valueOf(value));
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
