package com.agentframework.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentframework.crosscutting.guard.Guard;
import com.agentframework.crosscutting.guard.GuardContext;
import com.agentframework.crosscutting.guard.GuardDecision;
import com.agentframework.definition.DefinitionValidationException;
import com.agentframework.definition.agent.AgentDefinition;
import com.agentframework.definition.node.ConditionNodeDefinition;
import com.agentframework.definition.node.CustomNodeDefinition;
import com.agentframework.definition.node.LlmNodeDefinition;
import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.node.NodeMeta;
import com.agentframework.definition.node.NodeType;
import com.agentframework.definition.prompt.PromptDefinition;
import com.agentframework.definition.region.Paradigm;
import com.agentframework.definition.region.RegionDefinition;
import com.agentframework.definition.region.RegionLoop;
import com.agentframework.definition.workflow.DynamicPolicy;
import com.agentframework.definition.workflow.SlotType;
import com.agentframework.definition.workflow.WorkflowBuilder;
import com.agentframework.definition.workflow.WorkflowDefinition;
import com.agentframework.engine.core.Engine;
import com.agentframework.engine.core.EngineBuilder;
import com.agentframework.engine.core.EngineConfig;
import com.agentframework.engine.core.NodeContext;
import com.agentframework.engine.core.NodeResult;
import com.agentframework.engine.core.RunResult;
import com.agentframework.engine.workflowruntime.NodeExecutor;
import com.agentframework.infra.modelgateway.EchoModelProvider;
import com.agentframework.infra.storage.InMemoryEventBus;
import com.agentframework.runtime.event.Event;
import com.agentframework.runtime.event.Topics;
import com.agentframework.runtime.session.Input;
import com.agentframework.runtime.session.SessionState;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 动态边测试：白名单、回退、引擎开关、校验与循环协同。 */
class DynamicRoutingTest {

    @Test
    @DisplayName("白名单内的动态目标被采纳并留下审计事件")
    void allowedDynamicTargetIsTaken() {
        InMemoryEventBus events = new InMemoryEventBus();
        Engine engine = engine("b", List.of("a", "b"), null, events);

        RunResult result = engine.run("dynamic-agent", Input.of("开始"));

        assertEquals(SessionState.COMPLETED, result.state(), () -> "实际错误：" + result.error());
        assertEquals(List.of("router", "b"), result.visitedNodes());
        assertEquals("router->b", result.cursor().lastEdge());
        assertEquals(1L, events.count(Topics.ROUTE_DYNAMIC));
        assertEquals(0L, events.count(Topics.ROUTE_REJECTED));
    }

    @Test
    @DisplayName("白名单外的动态目标被拒绝并回退到静态边")
    void disallowedTargetFallsBackToStaticEdge() {
        InMemoryEventBus events = new InMemoryEventBus();
        Engine engine = engine("b", List.of("a"), null, events);

        RunResult result = engine.run("dynamic-agent", Input.of("开始"));

        assertEquals(SessionState.COMPLETED, result.state(), () -> "实际错误：" + result.error());
        assertEquals(List.of("router", "a"), result.visitedNodes());
        assertEquals(1L, events.count(Topics.ROUTE_REJECTED));
        assertTrue(reasonOf(events, Topics.ROUTE_REJECTED).contains("target_not_allowed"));
    }

    @Test
    @DisplayName("引擎级开关可以关闭动态路由")
    void engineSwitchDisablesDynamicRouting() {
        InMemoryEventBus events = new InMemoryEventBus();
        Engine engine = engine("b", List.of("a", "b"), EngineConfig.defaults().withoutDynamicRouting(), events);

        RunResult result = engine.run("dynamic-agent", Input.of("开始"));

        assertEquals(List.of("router", "a"), result.visitedNodes());
        assertTrue(reasonOf(events, Topics.ROUTE_REJECTED).contains("dynamic_routing_disabled"));
    }

    @Test
    @DisplayName("动态策略校验：未知节点、未知目标、非出边目标均阻断构建")
    void dynamicPolicyIsValidated() {
        assertThrows(DefinitionValidationException.class, () -> WorkflowBuilder.create("bad-1", "1.0.0")
                .node(LlmNodeDefinition.of("a", "p", "out").withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .edge("a", "a")
                .dynamic("missing-node", "a")
                .build());

        DefinitionValidationException unknownTarget = assertThrows(DefinitionValidationException.class,
                () -> WorkflowBuilder.create("bad-2", "1.0.0")
                        .node(LlmNodeDefinition.of("a", "p", "out"))
                        .node(LlmNodeDefinition.of("b", "p", "out2")
                                .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                        .node(LlmNodeDefinition.of("c", "p", "out3")
                                .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                        .edge("a", "b")
                        .dynamic("a", "missing")
                        .build());

        assertTrue(unknownTarget.getMessage().contains("DYNAMIC_TARGET_UNKNOWN"),
                () -> "实际消息：" + unknownTarget.getMessage());

        DefinitionValidationException notEdge = assertThrows(DefinitionValidationException.class,
                () -> WorkflowBuilder.create("bad-3", "1.0.0")
                        .node(LlmNodeDefinition.of("a", "p", "out"))
                        .node(LlmNodeDefinition.of("b", "p", "out2")
                                .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                        .node(LlmNodeDefinition.of("c", "p", "out3")
                                .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                        .edge("a", "b")
                        .edge("b", "c")
                        .dynamic("a", "c")
                        .build());

        assertTrue(notEdge.getMessage().contains("DYNAMIC_TARGET_NOT_EDGE"),
                () -> "实际消息：" + notEdge.getMessage());
    }

    @Test
    @DisplayName("存在动态策略时条件节点的确定性路由不受影响")
    void conditionRoutingIsUnaffected() {
        WorkflowDefinition workflow = WorkflowBuilder.create("mixed-wf", "1.0.0")
                .node(ConditionNodeDefinition.of("decide",
                        new ConditionNodeDefinition.Branch("b", "slots.flag == true"),
                        ConditionNodeDefinition.Branch.otherwise("a")))
                .node(LlmNodeDefinition.of("a", "prompt-a", "out_a")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .node(LlmNodeDefinition.of("b", "prompt-b", "out_b")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .edge("decide", "a")
                .slot("flag", SlotType.BOOLEAN)
                .slot("out_a", SlotType.STRING)
                .slot("out_b", SlotType.STRING)
                .dynamic("decide", "a")
                .build();
        Engine engine = EngineBuilder.create()
                .workflow(workflow)
                .prompt(PromptDefinition.template("prompt-a", "A"))
                .prompt(PromptDefinition.template("prompt-b", "B"))
                .modelProvider(new EchoModelProvider("echo", request -> "ok"))
                .defaultModelProvider("echo")
                .agent(agent("mixed", "mixed-wf"))
                .build();

        RunResult result = engine.run("mixed", Input.of("开始").withSlot("flag", true));

        assertEquals(List.of("decide", "b"), result.visitedNodes());
    }

    @Test
    @DisplayName("循环中断期间动态边不能停留在区域内")
    void loopBreakRejectsDynamicEdgeInsideRegion() {
        InMemoryEventBus events = new InMemoryEventBus();
        WorkflowDefinition workflow = WorkflowBuilder.create("loop-dynamic-wf", "1.0.0")
                .node(LlmNodeDefinition.of("entry", "prompt-entry", "state"))
                .node(new CustomNodeDefinition("exit", "dynamic-exit", null, "state",
                        NodeMeta.empty().withGuards("after-node-break")))
                .node(LlmNodeDefinition.of("report", "prompt-report", "report")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .edge("entry", "exit")
                .edgePriority("exit", "entry", 10)
                .edge("exit", "report")
                .slot("state", SlotType.STRING)
                .slot("report", SlotType.STRING)
                .region(RegionDefinition.of("r1", Paradigm.REFLECTION, "entry", "exit")
                        .withLoop(RegionLoop.of("entry", "exit", 2)))
                .dynamic("exit", "entry", "report")
                .build();
        Engine engine = EngineBuilder.create()
                .workflow(workflow)
                .prompt(PromptDefinition.template("prompt-entry", "进入"))
                .prompt(PromptDefinition.template("prompt-report", "报告"))
                .modelProvider(new EchoModelProvider("echo", request -> "ok"))
                .defaultModelProvider("echo")
                .agent(agent("loop-dynamic-agent", "loop-dynamic-wf"))
                .guard(afterNodeBreakAtExit())
                .nodeExecutor("dynamic-exit", new NodeExecutor() {
                    @Override
                    public NodeType type() {
                        return NodeType.CUSTOM;
                    }

                    @Override
                    public NodeResult execute(NodeDefinition node, NodeContext context) {
                        return NodeResult.dynamic(node.id(), "exit", "entry");
                    }
                })
                .events(events)
                .build();

        RunResult result = engine.run("loop-dynamic-agent", Input.of("开始"));

        assertEquals(SessionState.COMPLETED, result.state(), () -> "实际错误：" + result.error());
        assertEquals(List.of("entry", "exit", "entry", "exit", "report"), result.visitedNodes());
        assertTrue(events.count(Topics.ROUTE_DYNAMIC) >= 1L, () -> "路由事件：" + routeReasons(events));
        assertTrue(reasonOf(events, Topics.ROUTE_REJECTED).contains("loop_break_requires_exit"),
                () -> "路由事件：" + routeReasons(events));
    }

    /**
     * @param target 自定义节点的动态目标
     * @param allowed 白名单目标
     * @param config 引擎配置，可为 null
     * @param events 事件总线
     * @return 动态路由引擎
     */
    private Engine engine(String target, List<String> allowed, EngineConfig config, InMemoryEventBus events) {
        WorkflowBuilder builder = WorkflowBuilder.create("dynamic-wf", "1.0.0")
                .node(CustomNodeDefinition.of("router", "picker", "picked"))
                .node(LlmNodeDefinition.of("a", "prompt-a", "out_a")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .node(LlmNodeDefinition.of("b", "prompt-b", "out_b")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .edgePriority("router", "a", 5)
                .edge("router", "b")
                .slot("picked", SlotType.STRING)
                .slot("out_a", SlotType.STRING)
                .slot("out_b", SlotType.STRING)
                .dynamic("router", allowed.toArray(String[]::new));
        WorkflowDefinition workflow = builder.build();
        EngineBuilder engineBuilder = EngineBuilder.create()
                .workflow(workflow)
                .prompt(PromptDefinition.template("prompt-a", "A"))
                .prompt(PromptDefinition.template("prompt-b", "B"))
                .modelProvider(new EchoModelProvider("echo", request -> "ok"))
                .defaultModelProvider("echo")
                .agent(agent("dynamic-agent", "dynamic-wf"))
                .nodeExecutor("picker", new NodeExecutor() {
                    @Override
                    public NodeType type() {
                        return NodeType.CUSTOM;
                    }

                    @Override
                    public NodeResult execute(NodeDefinition node, NodeContext context) {
                        return NodeResult.dynamic(node.id(), "picked", target);
                    }
                })
                .events(events);
        if (config != null) {
            engineBuilder.config(config);
        }
        return engineBuilder.build();
    }

    /**
     * @param id         Agent id
     * @param workflowId 绑定的工作流 id
     * @return Agent 定义
     */
    private AgentDefinition agent(String id, String workflowId) {
        return AgentDefinition.builder(id).workflow(workflowId).model("echo", "echo").build();
    }

    /**
     * 构造只在出口节点 AFTER_NODE 阶段中断的守卫：模拟"节点跑完、产出了动态提议之后才判定中断"。
     *
     * @return 守卫
     */
    private Guard afterNodeBreakAtExit() {
        return new Guard() {
            @Override
            public String name() {
                return "after-node-break";
            }

            @Override
            public boolean supports(GuardContext context) {
                return context.phase() == com.agentframework.crosscutting.guard.GuardPhase.AFTER_NODE
                        && "exit".equals(context.nodeId());
            }

            @Override
            public GuardDecision check(GuardContext context) {
                return context.loopCounter("r1") >= 2
                        ? GuardDecision.breakLoop("iteration_limit")
                        : GuardDecision.allow();
            }
        };
    }

    /**
     * @param events 事件总线
     * @param type   事件类型
     * @return 首个匹配事件的 detail
     */
    private String reasonOf(InMemoryEventBus events, String type) {
        return events.history().stream()
                .filter(event -> event.type().equals(type))
                .map(Event::payload)
                .map(payload -> String.valueOf(payload.get("detail")))
                .findFirst()
                .orElse("");
    }

    /**
     * @param events 事件总线
     * @return 全部动态路由事件的描述，便于断言失败时定位
     */
    private List<String> routeReasons(InMemoryEventBus events) {
        return events.history().stream()
                .filter(event -> event.type().equals(Topics.ROUTE_DYNAMIC)
                        || event.type().equals(Topics.ROUTE_REJECTED))
                .map(event -> event.type() + "=" + event.payload().get("detail"))
                .toList();
    }
}
