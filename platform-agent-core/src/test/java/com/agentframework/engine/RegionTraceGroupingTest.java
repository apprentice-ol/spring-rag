package com.agentframework.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentframework.crosscutting.trace.RegionTraceGrouper;
import com.agentframework.crosscutting.trace.SimpleTracer;
import com.agentframework.crosscutting.trace.Span;
import com.agentframework.crosscutting.trace.SpanKind;
import com.agentframework.crosscutting.trace.TraceNode;
import com.agentframework.definition.agent.AgentDefinition;
import com.agentframework.definition.node.LlmNodeDefinition;
import com.agentframework.definition.node.NodeMeta;
import com.agentframework.definition.prompt.PromptDefinition;
import com.agentframework.definition.region.Paradigm;
import com.agentframework.definition.region.RegionDefinition;
import com.agentframework.definition.workflow.SlotType;
import com.agentframework.definition.workflow.WorkflowBuilder;
import com.agentframework.definition.workflow.WorkflowDefinition;
import com.agentframework.engine.core.Engine;
import com.agentframework.engine.core.EngineBuilder;
import com.agentframework.infra.modelgateway.EchoModelProvider;
import com.agentframework.infra.storage.InMemorySpanExporter;
import com.agentframework.runtime.session.Input;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Region 追踪分组测试：导出侧分组，不改 Span 模型。 */
class RegionTraceGroupingTest {

    private final RegionTraceGrouper grouper = new RegionTraceGrouper();

    @Test
    @DisplayName("按区域分组并保留区域内父子层级")
    void groupsSpansByRegion() {
        Span workflow = new Span("t1", null, "workflow:pipeline", SpanKind.WORKFLOW);
        Span classify = new Span("t1", workflow.id(), "node:classify", SpanKind.NODE);
        classify.setAttribute(RegionTraceGrouper.REGION_ATTRIBUTE, "r1");
        classify.setAttribute(RegionTraceGrouper.PARADIGM_ATTRIBUTE, "ROUTER");
        Span llm = new Span("t1", classify.id(), "llm:classify", SpanKind.LLM);
        llm.setAttribute(RegionTraceGrouper.REGION_ATTRIBUTE, "r1");
        llm.end();
        classify.end();
        Span route = new Span("t1", workflow.id(), "node:route", SpanKind.NODE);
        route.setAttribute(RegionTraceGrouper.REGION_ATTRIBUTE, "r2");
        route.setAttribute(RegionTraceGrouper.PARADIGM_ATTRIBUTE, "HUMAN_IN_LOOP");
        route.end();
        workflow.end();

        TraceNode root = grouper.group("agent:test", List.of(workflow, classify, llm, route));

        assertEquals(3, root.children().size());
        TraceNode first = root.children().get(0);
        assertEquals("r1", first.regionId());
        assertEquals("ROUTER", first.paradigm());
        assertEquals("[ROUTER] r1", first.label());
        assertEquals(1, first.children().size());
        assertEquals("node:classify", first.children().get(0).label());
        assertEquals("llm:classify", first.children().get(0).children().get(0).label());
        assertEquals(2, first.spanCount());
        assertEquals(RegionTraceGrouper.UNASSIGNED, root.children().get(2).name());
        assertEquals(1, root.children().get(2).spanCount());

        String rendered = grouper.render(root);
        assertTrue(rendered.contains("Trace: agent:test"), rendered);
        assertTrue(rendered.contains("[ROUTER] r1 (2 spans)"), rendered);
        assertTrue(rendered.contains("node:classify"), rendered);
        assertTrue(rendered.contains("llm:classify"), rendered);
        assertTrue(rendered.contains("[HUMAN_IN_LOOP] r2"), rendered);
        assertTrue(rendered.contains("unassigned"), rendered);
    }

    @Test
    @DisplayName("运行时写入的 region 属性让导出结果可直接分组")
    void exportedSpansAreGroupable() {
        InMemorySpanExporter exporter = new InMemorySpanExporter();
        Engine engine = EngineBuilder.create()
                .workflow(regionWorkflow())
                .prompt(PromptDefinition.template("hello-prompt", "问候：{{messages}}"))
                .modelProvider(new EchoModelProvider("echo", request -> "你好"))
                .defaultModelProvider("echo")
                .agent(AgentDefinition.builder("region-trace-agent").workflow("region-trace-wf")
                        .model("echo", "echo").build())
                .tracer(new SimpleTracer(true, null, List.of(exporter)))
                .build();

        engine.run("region-trace-agent", Input.of("你好"));

        TraceNode root = grouper.group("agent:region-trace-agent", exporter.spans());
        TraceNode region = root.children().stream()
                .filter(node -> "r1".equals(node.regionId()))
                .findFirst()
                .orElseThrow();
        TraceNode unassigned = root.children().stream()
                .filter(node -> RegionTraceGrouper.UNASSIGNED.equals(node.name()))
                .findFirst()
                .orElseThrow();

        assertTrue(spanNames(region).contains("node:hello"), () -> "实际：" + spanNames(region));
        assertEquals("ROUTER", region.paradigm());
        assertTrue(spanNames(unassigned).contains("agent:region-trace-agent"),
                () -> "实际：" + spanNames(unassigned));
        assertTrue(spanNames(unassigned).contains("workflow:region-trace-wf"),
                () -> "实际：" + spanNames(unassigned));
    }

    /**
     * @return 单节点且声明 Region 的工作流
     */
    private WorkflowDefinition regionWorkflow() {
        return WorkflowBuilder.create("region-trace-wf", "1.0.0")
                .node(LlmNodeDefinition.of("hello", "hello-prompt", "greeting")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .slot("greeting", SlotType.STRING)
                .region(RegionDefinition.of("r1", Paradigm.ROUTER, "hello"))
                .build();
    }

    /**
     * @param node 节点
     * @return 递归收集的 Span 名
     */
    private List<String> spanNames(TraceNode node) {
        List<String> names = new ArrayList<>();
        if (!node.isGroup()) {
            names.add(node.span().name());
        }
        node.children().forEach(child -> names.addAll(spanNames(child)));
        return names;
    }
}
