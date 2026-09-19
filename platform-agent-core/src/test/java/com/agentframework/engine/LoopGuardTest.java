package com.agentframework.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentframework.crosscutting.guard.Guard;
import com.agentframework.crosscutting.guard.GuardContext;
import com.agentframework.crosscutting.guard.GuardDecision;
import com.agentframework.crosscutting.guard.GuardPhase;
import com.agentframework.crosscutting.guard.Guards;
import com.agentframework.definition.agent.AgentDefinition;
import com.agentframework.definition.node.CustomNodeDefinition;
import com.agentframework.definition.node.LlmNodeDefinition;
import com.agentframework.definition.node.NodeMeta;
import com.agentframework.definition.node.NodeType;
import com.agentframework.definition.prompt.PromptDefinition;
import com.agentframework.definition.region.LoopConvergence;
import com.agentframework.definition.region.Paradigm;
import com.agentframework.definition.region.RegionDefinition;
import com.agentframework.definition.region.RegionLoop;
import com.agentframework.definition.workflow.SlotType;
import com.agentframework.definition.workflow.WorkflowBuilder;
import com.agentframework.definition.workflow.WorkflowDefinition;
import com.agentframework.engine.core.Engine;
import com.agentframework.engine.core.EngineBuilder;
import com.agentframework.engine.core.EngineConfig;
import com.agentframework.engine.core.NodeContext;
import com.agentframework.engine.core.NodeResult;
import com.agentframework.engine.core.RunResult;
import com.agentframework.engine.policy.Activation;
import com.agentframework.engine.policy.RegionMetrics;
import com.agentframework.engine.workflowruntime.NodeExecutor;
import com.agentframework.infra.modelgateway.EchoModelProvider;
import com.agentframework.infra.storage.FileSessionStore;
import com.agentframework.infra.storage.InMemoryEventBus;
import com.agentframework.runtime.event.Topics;
import com.agentframework.runtime.session.Cursor;
import com.agentframework.runtime.session.Input;
import com.agentframework.runtime.session.SessionRecord;
import com.agentframework.runtime.session.SessionState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 循环守卫与可回放测试：迭代上限、回边跳过、计数与 lastEdge 持久化。 */
class LoopGuardTest {

    @Test
    @DisplayName("迭代上限守卫在达到上限后跳过回边并沿前向边继续")
    void loopGuardBreaksAndContinuesForward() {
        InMemoryEventBus events = new InMemoryEventBus();
        Engine engine = engine(2, events, null);

        RunResult result = engine.run("loop-agent", Input.of("开始"));

        assertEquals(SessionState.COMPLETED, result.state(), () -> "实际错误：" + result.error());
        assertEquals(List.of("work", "work", "done"), result.visitedNodes());
        assertTrue(events.count(Topics.LOOP_BREAK) >= 1L);
    }

    @Test
    @DisplayName("循环计数与 lastEdge 写入游标并可持久化")
    void loopCountersAndLastEdgeArePersisted() throws Exception {
        Engine engine = engine(2, new InMemoryEventBus(), null);

        RunResult result = engine.run("loop-agent", Input.of("开始"));

        assertEquals(2, result.cursor().loopCounters().get("work"));
        assertEquals("work->done", result.cursor().lastEdge());

        Path dir = Files.createTempDirectory("agent-framework-loop");
        try {
            FileSessionStore store = new FileSessionStore(dir);
            store.save(new SessionRecord("s-loop", null, null, "loop-agent", "latest", "loop-wf", "1.0.0",
                    SessionState.SUSPENDED, result.cursor().withState("loop.r1.delta", 0.25), List.of(), null, null,
                    Instant.now(), Instant.now(), null, null));

            SessionRecord loaded = store.load("s-loop").orElseThrow();

            assertEquals(2, loaded.cursor().loopCounters().get("work"));
            assertEquals("work->done", loaded.cursor().lastEdge());
            assertEquals("done", loaded.cursor().nodeId());
            assertEquals(0.25, loaded.cursor().state().get("loop.r1.delta"));
        } finally {
            try (var files = Files.list(dir)) {
                files.forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                        // 临时文件清理失败不影响断言
                    }
                });
            }
            Files.deleteIfExists(dir);
        }
    }

    @Test
    @DisplayName("没有循环守卫时回边会一直执行直到步数上限")
    void withoutLoopGuardBackEdgeRunsUntilStepLimit() {
        Engine engine = engine(0, new InMemoryEventBus(), EngineConfig.defaults().withMaxSteps(5));

        RunResult result = engine.run("loop-agent", Input.of("开始"));

        assertEquals(SessionState.FAILED, result.state());
        assertTrue(result.error().contains("最大步数"), () -> "实际错误：" + result.error());
    }

    @Test
    @DisplayName("Region 声明循环后按循环标识计数，中断时只走区域外的前向边")
    void regionLoopUsesLoopIdentity() {
        InMemoryEventBus events = new InMemoryEventBus();
        WorkflowDefinition workflow = WorkflowBuilder.create("region-loop-wf", "1.0.0")
                .node(LlmNodeDefinition.of("aggregate", "aggregate-prompt", "hypothesis"))
                .node(LlmNodeDefinition.of("critique", "critique-prompt", "verdict")
                        .withMeta(NodeMeta.empty().withGuards("max-iterations")))
                .node(LlmNodeDefinition.of("report", "report-prompt", "report")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .edge("aggregate", "critique")
                .edgePriority("critique", "aggregate", 10)
                .edge("critique", "report")
                .slot("hypothesis", SlotType.STRING)
                .slot("verdict", SlotType.STRING)
                .slot("report", SlotType.STRING)
                .slot("iteration", SlotType.NUMBER)
                .region(RegionDefinition.of("r3_reflection", Paradigm.REFLECTION, "aggregate", "critique")
                        .withLoop(RegionLoop.of("aggregate", "critique", 3).withCounterSlot("iteration")))
                .build();
        Engine engine = EngineBuilder.create()
                .workflow(workflow)
                .prompt(PromptDefinition.template("aggregate-prompt", "聚合"))
                .prompt(PromptDefinition.template("critique-prompt", "批判"))
                .prompt(PromptDefinition.template("report-prompt", "报告"))
                .modelProvider(new EchoModelProvider("echo", request -> "ok"))
                .defaultModelProvider("echo")
                .agent(AgentDefinition.builder("reflection-agent").workflow("region-loop-wf")
                        .model("echo", "echo").build())
                .loopGuard("max-iterations", 3)
                .events(events)
                .build();

        RunResult result = engine.run("reflection-agent", Input.of("开始"));

        assertEquals(SessionState.COMPLETED, result.state(), () -> "实际错误：" + result.error());
        assertEquals(List.of("aggregate", "critique", "aggregate", "critique", "aggregate", "critique", "report"),
                result.visitedNodes());
        assertEquals(3, result.cursor().loopCounters().get("r3_reflection"));
        assertNull(result.cursor().loopCounters().get("aggregate"), "Region 循环不应再按节点计数");
        assertEquals("critique->report", result.cursor().lastEdge());
        assertEquals(3, result.slots().get("iteration"));
        assertTrue(events.count(Topics.LOOP_BREAK) >= 1L);
        RegionMetrics metrics = engine.regionMetrics(result.sessionId()).get("r3_reflection");
        assertEquals(3, metrics.iterationCount());
        assertEquals(6, metrics.nodeCount());
    }

    @Test
    @DisplayName("成本预算守卫在 Region token 用量超限时中断循环")
    void costBudgetGuardBreaksLoop() {
        InMemoryEventBus events = new InMemoryEventBus();
        Engine engine = EngineBuilder.create()
                .workflow(reflectionWorkflow(RegionLoop.of("aggregate", "critique", 50)))
                .prompt(PromptDefinition.template("aggregate-prompt", "聚合"))
                .prompt(PromptDefinition.template("critique-prompt", "批判"))
                .prompt(PromptDefinition.template("report-prompt", "报告"))
                .modelProvider(new EchoModelProvider("echo", request -> "ok"))
                .defaultModelProvider("echo")
                .agent(AgentDefinition.builder("cost-agent").workflow("region-loop-wf")
                        .model("echo", "echo").build())
                .component("cost-budget", Activation.DEFAULT_OFF, Guards.costBudget("cost-budget", 4))
                .events(events)
                .build();

        RunResult result = engine.run("cost-agent", Input.of("开始"));

        assertEquals(SessionState.COMPLETED, result.state(), () -> "实际错误：" + result.error());
        assertTrue(events.count(Topics.LOOP_BREAK) >= 1L);
        assertEquals("report", result.visitedNodes().get(result.visitedNodes().size() - 1));
        assertTrue(result.cursor().loopCounters().get("r3_reflection") < 50);
    }

    @Test
    @DisplayName("收敛判定在相邻迭代改进不足时中断循环")
    void convergenceBreaksLoop() {
        InMemoryEventBus events = new InMemoryEventBus();
        WorkflowDefinition workflow = WorkflowBuilder.create("region-loop-wf", "1.0.0")
                .node(CustomNodeDefinition.of("aggregate", "score-writer", "score"))
                .node(LlmNodeDefinition.of("critique", "critique-prompt", "verdict")
                        .withMeta(NodeMeta.empty().withGuards("max-iterations")))
                .node(LlmNodeDefinition.of("report", "report-prompt", "report")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .edge("aggregate", "critique")
                .edgePriority("critique", "aggregate", 10)
                .edge("critique", "report")
                .slot("score", SlotType.NUMBER)
                .slot("verdict", SlotType.STRING)
                .slot("report", SlotType.STRING)
                .region(RegionDefinition.of("r3_reflection", Paradigm.REFLECTION, "aggregate", "critique")
                        .withLoop(RegionLoop.of("aggregate", "critique", 50)
                                .withConvergence(LoopConvergence.of("score", 0.5))))
                .build();
        Engine engine = EngineBuilder.create()
                .workflow(workflow)
                .prompt(PromptDefinition.template("critique-prompt", "批判"))
                .prompt(PromptDefinition.template("report-prompt", "报告"))
                .modelProvider(new EchoModelProvider("echo", request -> "ok"))
                .defaultModelProvider("echo")
                .agent(AgentDefinition.builder("converge-agent").workflow("region-loop-wf")
                        .model("echo", "echo").build())
                .loopGuard("max-iterations", 50)
                .nodeExecutor("score-writer", new NodeExecutor() {
                    @Override
                    public NodeType type() {
                        return NodeType.CUSTOM;
                    }

                    @Override
                    public NodeResult execute(com.agentframework.definition.node.NodeDefinition node,
                            NodeContext context) {
                        return NodeResult.completed(node.id(), "1.0", java.util.Map.of("score", 1.0));
                    }
                })
                .events(events)
                .build();

        RunResult result = engine.run("converge-agent", Input.of("开始"));

        assertEquals(SessionState.COMPLETED, result.state(), () -> "实际错误：" + result.error());
        assertEquals(List.of("aggregate", "critique", "aggregate", "critique", "report"), result.visitedNodes());
        assertTrue(events.count(Topics.LOOP_BREAK) >= 1L);
        assertEquals(2, result.cursor().loopCounters().get("r3_reflection"));
    }

    /**
     * @param loop 循环声明
     * @return 反思型循环工作流，出口节点绑定成本守卫
     */
    private WorkflowDefinition reflectionWorkflow(RegionLoop loop) {
        return WorkflowBuilder.create("region-loop-wf", "1.0.0")
                .node(LlmNodeDefinition.of("aggregate", "aggregate-prompt", "hypothesis"))
                .node(LlmNodeDefinition.of("critique", "critique-prompt", "verdict")
                        .withMeta(NodeMeta.empty().withGuards("cost-budget")))
                .node(LlmNodeDefinition.of("report", "report-prompt", "report")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .edge("aggregate", "critique")
                .edgePriority("critique", "aggregate", 10)
                .edge("critique", "report")
                .slot("hypothesis", SlotType.STRING)
                .slot("verdict", SlotType.STRING)
                .slot("report", SlotType.STRING)
                .region(RegionDefinition.of("r3_reflection", Paradigm.REFLECTION, "aggregate", "critique")
                        .withLoop(loop))
                .build();
    }

    @Test
    @DisplayName("Agent 作用域的 BreakLoop 是优雅停止：输入阶段停止、输出阶段保留结果")
    void agentScopeBreakLoopStopsGracefully() {
        InMemoryEventBus events = new InMemoryEventBus();
        Engine inputBreak = simpleEngine(phaseGuard(GuardPhase.BEFORE_AGENT), events);

        RunResult early = inputBreak.run("loop-agent", Input.of("开始"));

        assertEquals(SessionState.COMPLETED, early.state(), () -> "实际错误：" + early.error());
        assertTrue(early.visitedNodes().isEmpty());
        assertEquals("", early.output());
        assertTrue(events.count(Topics.LOOP_BREAK) >= 1L);

        InMemoryEventBus outputEvents = new InMemoryEventBus();
        Engine outputBreak = simpleEngine(phaseGuard(GuardPhase.BEFORE_OUTPUT), outputEvents);

        RunResult late = outputBreak.run("loop-agent", Input.of("开始"));

        assertEquals(SessionState.COMPLETED, late.state(), () -> "实际错误：" + late.error());
        assertFalse(late.visitedNodes().isEmpty());
        assertEquals("ok", late.output());
        assertTrue(outputEvents.count(Topics.LOOP_BREAK) >= 1L);
    }

    /**
     * @param phase 触发中断的挂载点
     * @return 只在该挂载点返回 BreakLoop 的守卫
     */
    private Guard phaseGuard(GuardPhase phase) {
        return new Guard() {
            @Override
            public String name() {
                return "phase-break";
            }

            @Override
            public boolean supports(GuardContext context) {
                return context.phase() == phase;
            }

            @Override
            public GuardDecision check(GuardContext context) {
                return GuardDecision.breakLoop("agent_scope_stop");
            }
        };
    }

    /**
     * @param guard  参与解析的守卫
     * @param events 事件总线
     * @return 单节点引擎
     */
    private Engine simpleEngine(Guard guard, InMemoryEventBus events) {
        WorkflowDefinition workflow = WorkflowBuilder.create("simple-wf", "1.0.0")
                .node(LlmNodeDefinition.of("hello", "hello-prompt", "greeting")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .slot("greeting", SlotType.STRING)
                .build();
        return EngineBuilder.create()
                .workflow(workflow)
                .prompt(PromptDefinition.template("hello-prompt", "问候：{{messages}}"))
                .modelProvider(new EchoModelProvider("echo", request -> "ok"))
                .defaultModelProvider("echo")
                .agent(AgentDefinition.builder("loop-agent").workflow("simple-wf").model("echo", "echo").build())
                .guard(guard)
                .events(events)
                .build();
    }

    /**
     * @param maxIterations 迭代上限，≤0 表示不注册守卫
     * @param events        事件总线
     * @param config        引擎配置，可为 null
     * @return 带自环的引擎
     */
    private Engine engine(int maxIterations, InMemoryEventBus events, EngineConfig config) {
        NodeMeta workMeta = maxIterations > 0
                ? NodeMeta.empty().withGuards("max-iterations")
                : NodeMeta.empty();
        WorkflowDefinition workflow = WorkflowBuilder.create("loop-wf", "1.0.0")
                .node(LlmNodeDefinition.of("work", "work-prompt", "work_out").withMeta(workMeta))
                .node(LlmNodeDefinition.of("done", "done-prompt", "done_out")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .edgePriority("work", "work", 10)
                .edge("work", "done")
                .slot("work_out", SlotType.STRING)
                .slot("done_out", SlotType.STRING)
                .build();
        EngineBuilder builder = EngineBuilder.create()
                .workflow(workflow)
                .prompt(PromptDefinition.template("work-prompt", "循环体"))
                .prompt(PromptDefinition.template("done-prompt", "收尾"))
                .modelProvider(new EchoModelProvider("echo", request -> "ok"))
                .defaultModelProvider("echo")
                .agent(AgentDefinition.builder("loop-agent").workflow("loop-wf").model("echo", "echo").build())
                .events(events);
        if (config != null) {
            builder.config(config);
        }
        if (maxIterations > 0) {
            builder.loopGuard("max-iterations", maxIterations);
        }
        return builder.build();
    }
}
