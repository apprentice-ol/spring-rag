package com.agentframework.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentframework.crosscutting.trace.RegionTraceGrouper;
import com.agentframework.crosscutting.trace.SimpleTracer;
import com.agentframework.crosscutting.trace.TraceNode;
import com.agentframework.crosscutting.trace.TraceView;
import com.agentframework.crosscutting.trace.TraceViewFactory;
import com.agentframework.definition.ValidationProblem;
import com.agentframework.definition.agent.AgentDefinition;
import com.agentframework.definition.codec.JsonSupport;
import com.agentframework.definition.node.ConditionNodeDefinition;
import com.agentframework.definition.node.LlmNodeDefinition;
import com.agentframework.definition.node.NodeMeta;
import com.agentframework.definition.prompt.PromptDefinition;
import com.agentframework.definition.region.Paradigm;
import com.agentframework.definition.region.RegionDefinition;
import com.agentframework.definition.region.RegionLoop;
import com.agentframework.definition.view.DefinitionView;
import com.agentframework.definition.view.DefinitionViewFactory;
import com.agentframework.definition.view.EdgeKind;
import com.agentframework.definition.view.EdgeView;
import com.agentframework.definition.view.NodeView;
import com.agentframework.definition.view.RegionView;
import com.agentframework.definition.workflow.SlotType;
import com.agentframework.definition.workflow.WorkflowBuilder;
import com.agentframework.definition.workflow.WorkflowDefinition;
import com.agentframework.engine.core.Engine;
import com.agentframework.engine.core.EngineBuilder;
import com.agentframework.engine.core.RunResult;
import com.agentframework.engine.view.SessionView;
import com.agentframework.engine.view.SessionViewFactory;
import com.agentframework.infra.modelgateway.EchoModelProvider;
import com.agentframework.infra.storage.InMemorySpanExporter;
import com.agentframework.runtime.session.Input;
import com.agentframework.runtime.session.Session;
import com.agentframework.runtime.session.SessionState;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 读模型测试：定义视图、会话视图、Trace 视图与 location 契约。 */
class ReadModelTest {

    @Test
    @DisplayName("定义视图覆盖节点、三类边、区域与循环，并可直接序列化")
    void definitionViewCoversTopology() {
        DefinitionView view = DefinitionViewFactory.of(regionWorkflow());

        assertEquals("workflow", view.kind());
        assertEquals(3, view.nodes().size());
        assertTrue(view.edges().stream().anyMatch(edge -> edge.kind() == EdgeKind.STATIC));
        assertTrue(view.edges().stream().anyMatch(edge -> edge.kind() == EdgeKind.CONDITION));
        assertTrue(view.edges().stream().anyMatch(edge -> edge.kind() == EdgeKind.DYNAMIC));
        RegionView region = view.regions().get(0);
        assertEquals("r1", region.id());
        assertEquals("REFLECTION", region.paradigm());
        assertEquals(2, region.nodeIds().size());
        assertEquals(3, region.loop().maxIterations());
        assertEquals("iteration", region.loop().counterSlot());
        NodeView decide = view.nodes().stream().filter(node -> node.id().equals("decide")).findFirst().orElseThrow();
        assertEquals("r1", decide.regionId());

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = (Map<String, Object>) JsonSupport.parse(JsonSupport.write(view.toDocument()));
        assertEquals(3, ((List<?>) parsed.get("nodes")).size());
        assertEquals(1, ((List<?>) parsed.get("regions")).size());
        assertFalse(((List<?>) parsed.get("edges")).isEmpty());
    }

    @Test
    @DisplayName("location 语法是前端契约，保持稳定")
    void locationContractIsStable() {
        WorkflowDefinition workflow = WorkflowBuilder.create("contract-wf", "1.0.0")
                .node(LlmNodeDefinition.of("plan", "prompt-plan", "out")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .slot("out", SlotType.STRING)
                .region(RegionDefinition.of("r1", Paradigm.ROUTER, "ghost"))
                .buildUnvalidated();

        ValidationProblem problem = workflow.validateReport().errors().stream()
                .filter(candidate -> candidate.code().equals("REGION_NODE_UNKNOWN"))
                .findFirst()
                .orElseThrow();

        assertEquals("workflow:contract-wf@1.0.0/region:r1/node:ghost", problem.location());
    }

    @Test
    @DisplayName("会话视图反映游标、区域指标与最近步")
    void sessionViewReflectsRuntime() {
        Engine engine = engine(regionWorkflow(), new InMemorySpanExporter());
        Session session = engine.startSession(engine.loadAgent("read-model-agent", "latest"),
                com.agentframework.runtime.session.StartOptions.defaults());

        RunResult result = engine.run(session, Input.of("开始"));
        SessionView view = SessionViewFactory.of(engine, session);

        assertEquals(SessionState.COMPLETED.name(), view.state());
        assertEquals("report", view.currentNode());
        assertTrue(view.step() > 0);
        assertEquals("critique->report", view.lastEdge());
        assertTrue(view.regionMetrics().containsKey("r1"));
        assertEquals(3, view.regionMetrics().get("r1").iterationCount());
        assertFalse(view.recentSteps().isEmpty());

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = (Map<String, Object>) JsonSupport.parse(JsonSupport.write(view.toDocument()));
        assertEquals("r1", ((Map<?, ?>) parsed.get("regionMetrics")).keySet().iterator().next());
        assertEquals(SessionState.COMPLETED.name(), parsed.get("state"));
    }

    @Test
    @DisplayName("Trace 视图保留区域分组与父子层级")
    void traceViewKeepsRegionGrouping() {
        InMemorySpanExporter exporter = new InMemorySpanExporter();
        Engine engine = engine(regionWorkflow(), exporter);

        engine.run("read-model-agent", Input.of("开始"));

        RegionTraceGrouper grouper = new RegionTraceGrouper();
        TraceNode root = grouper.group("agent:read-model-agent", exporter.spans());
        TraceView view = TraceViewFactory.of(root);

        TraceView region = view.children().stream()
                .filter(child -> "r1".equals(child.regionId()))
                .findFirst()
                .orElseThrow();
        assertEquals("REFLECTION", region.paradigm());
        assertTrue(region.spanCount() > 0);
        assertFalse(region.children().isEmpty());
        assertTrue(JsonSupport.write(view.toDocument()).contains("critique"));
    }

    /**
     * @return 带区域循环、条件边与动态边的三节点工作流
     */
    private WorkflowDefinition regionWorkflow() {
        return WorkflowBuilder.create("read-model-wf", "1.0.0")
                .node(LlmNodeDefinition.of("decide", "prompt-decide", "intent"))
                .node(LlmNodeDefinition.of("critique", "prompt-critique", "score")
                        .withMeta(NodeMeta.empty().withGuards("max-iterations")))
                .node(LlmNodeDefinition.of("report", "prompt-report", "report")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .when("decide", "critique", "slots.intent != ''")
                .edgePriority("critique", "decide", 10)
                .edge("critique", "report")
                .slot("intent", SlotType.STRING)
                .slot("score", SlotType.NUMBER)
                .slot("iteration", SlotType.NUMBER)
                .slot("report", SlotType.STRING)
                .region(RegionDefinition.of("r1", Paradigm.REFLECTION, "decide", "critique")
                        .withLoop(RegionLoop.of("decide", "critique", 3).withCounterSlot("iteration")))
                .dynamic("decide", "critique")
                .build();
    }

    /**
     * @param workflow 工作流
     * @param exporter Span 导出器
     * @return 引擎
     */
    private Engine engine(WorkflowDefinition workflow, InMemorySpanExporter exporter) {
        return EngineBuilder.create()
                .workflow(workflow)
                .prompt(PromptDefinition.template("prompt-decide", "判断"))
                .prompt(PromptDefinition.template("prompt-critique", "批判"))
                .prompt(PromptDefinition.template("prompt-report", "报告"))
                .modelProvider(new EchoModelProvider("echo", request -> "ok"))
                .defaultModelProvider("echo")
                .agent(AgentDefinition.builder("read-model-agent").workflow("read-model-wf")
                        .model("echo", "echo").build())
                .tracer(new SimpleTracer(true, null, List.of(exporter)))
                .loopGuard("max-iterations", 3)
                .build();
    }
}
