package com.agentframework.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentframework.crosscutting.metrics.InMemoryMetrics;
import com.agentframework.crosscutting.trace.SimpleTracer;
import com.agentframework.definition.agent.AgentDefinition;
import com.agentframework.definition.node.CustomNodeDefinition;
import com.agentframework.definition.node.LlmNodeDefinition;
import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.node.NodeMeta;
import com.agentframework.definition.node.NodeType;
import com.agentframework.definition.node.ParallelNodeDefinition;
import com.agentframework.definition.node.SubWorkflowNodeDefinition;
import com.agentframework.definition.policy.ToolPolicy;
import com.agentframework.definition.prompt.PromptDefinition;
import com.agentframework.definition.workflow.SlotType;
import com.agentframework.definition.workflow.WorkflowBuilder;
import com.agentframework.definition.workflow.WorkflowDefinition;
import com.agentframework.engine.core.Engine;
import com.agentframework.engine.core.EngineBuilder;
import com.agentframework.engine.core.NodeContext;
import com.agentframework.engine.core.NodeResult;
import com.agentframework.engine.core.RunResult;
import com.agentframework.engine.workflowruntime.NodeExecutor;
import com.agentframework.infra.modelgateway.EchoModelProvider;
import com.agentframework.infra.modelgateway.ScriptedModelProvider;
import com.agentframework.infra.storage.InMemoryEventBus;
import com.agentframework.infra.storage.InMemorySpanExporter;
import com.agentframework.runtime.event.Topics;
import com.agentframework.runtime.session.Input;
import com.agentframework.runtime.session.MessageRole;
import com.agentframework.runtime.session.Session;
import com.agentframework.runtime.session.SessionState;
import com.agentframework.runtime.session.StartOptions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 引擎端到端测试：完整执行链、缓存、自定义节点、并行节点与子工作流。 */
class EngineExecutionTest {

    private InMemoryEventBus events;
    private InMemoryMetrics metrics;
    private InMemorySpanExporter exporter;

    @Test
    @DisplayName("完整执行链：LLM → 工具 → 条件 → LLM，并产出槽位、消息、事件、指标与链路")
    void fullExecutionChain() {
        ScriptedModelProvider model = new ScriptedModelProvider("scripted")
                .enqueueText("第一步：先做加法", "结论：计算结果是 5");
        Engine engine = engine(EngineFixture.agent(), EngineFixture.pipeline(), model);

        RunResult result = engine.run(EngineFixture.AGENT_ID,
                Input.of("帮我算一下").withSlot("question", "2 加 3 等于几").withSlot("a", 2).withSlot("b", 3));

        assertEquals(SessionState.COMPLETED, result.state());
        assertEquals(List.of("plan", "calc", "decide", "report"), result.visitedNodes());
        assertEquals("结果：5", result.slots().get("calc_result"));
        assertEquals("结论：计算结果是 5", result.output());
        assertEquals(2, model.requests().size());
        assertTrue(result.messages().stream().anyMatch(message -> message.role() == MessageRole.TOOL));
        assertEquals(2, result.messages().stream()
                .filter(message -> message.role() == MessageRole.ASSISTANT).count());
        assertEquals(1L, events.count(Topics.SESSION_COMPLETED));
        assertEquals(4L, events.count(Topics.NODE_COMPLETED) + events.count(Topics.NODE_FAILED));
        assertEquals(2L, metrics.counterValue("model.calls"));
        assertTrue(metrics.counterValue("node.calls") >= 4);
        assertEquals(1, exporter.spansNamed("node:plan").size());
        assertEquals(1, exporter.spansNamed("llm:plan").size());
        assertEquals(1, exporter.spansNamed("tool:calculator").size());
        assertEquals(1, exporter.spansNamed("agent:" + EngineFixture.AGENT_ID).size());
        assertEquals(1, exporter.spansNamed("workflow:pipeline").size());
    }

    @Test
    @DisplayName("相同输入重复运行命中缓存，不再调用模型")
    void llmCacheHitsOnSecondRun() {
        ScriptedModelProvider model = new ScriptedModelProvider("scripted")
                .enqueueText("第一步：先做加法", "结论：计算结果是 5");
        Engine engine = engine(EngineFixture.agent(), EngineFixture.pipeline(), model);
        Session session = engine.startSession(engine.loadAgent(EngineFixture.AGENT_ID, "latest"),
                StartOptions.builder()
                        .slot("question", "2 加 3 等于几")
                        .slot("a", 2)
                        .slot("b", 3)
                        .build());

        engine.run(session, Input.of("第一次"));
        assertEquals(2, model.requests().size());

        RunResult second = engine.run(session, Input.of("第二次"));

        assertEquals(SessionState.COMPLETED, second.state());
        assertEquals(2, model.requests().size(), "第二次运行应命中缓存，而不是再次调用模型");
        assertEquals(List.of("report"), second.visitedNodes());
    }

    @Test
    @DisplayName("工具策略拒绝未授权工具时会话失败")
    void toolPolicyRejectsUnauthorizedTool() {
        ScriptedModelProvider model = new ScriptedModelProvider("scripted").enqueueText("计划");
        Engine engine = engine(EngineFixture.agent(ToolPolicy.only("other-tool"), null),
                EngineFixture.pipeline(), model);

        RunResult result = engine.run(EngineFixture.AGENT_ID,
                Input.of("帮我算一下").withSlot("question", "2+3").withSlot("a", 2).withSlot("b", 3));

        assertEquals(SessionState.FAILED, result.state());
        assertTrue(result.error().contains("工具"), () -> "实际错误：" + result.error());
    }

    @Test
    @DisplayName("自定义节点通过注册名执行")
    void customNodeExecutor() {
        PromptDefinition prompt = PromptDefinition.template("unused", "未使用");
        WorkflowDefinition workflow = WorkflowBuilder.create("custom-wf", "1.0.0")
                .node(CustomNodeDefinition.of("upper", "upper-case", "custom_out"))
                .slot("custom_out", SlotType.STRING)
                .build();
        Engine engine = EngineBuilder.create()
                .workflow(workflow)
                .prompt(prompt)
                .agent(AgentDefinition.builder("custom-agent").workflow("custom-wf").build())
                .nodeExecutor("upper-case", new NodeExecutor() {
                    @Override
                    public NodeType type() {
                        return NodeType.CUSTOM;
                    }

                    @Override
                    public NodeResult execute(NodeDefinition node, NodeContext context) {
                        String text = context.slots().getString("question", "empty").toUpperCase();
                        return NodeResult.completed(node.id(), text, Map.of("custom_out", text));
                    }
                })
                .build();

        RunResult result = engine.run("custom-agent", Input.of("hello").withSlot("question", "hello"));

        assertEquals("HELLO", result.slots().get("custom_out"));
        assertEquals("HELLO", result.output());
    }

    @Test
    @DisplayName("并行节点扇出执行并汇聚分支结果")
    void parallelNodeJoinsBranches() {
        WorkflowDefinition workflow = WorkflowBuilder.create("parallel-wf", "1.0.0")
                .node(ParallelNodeDefinition.of("fan-out", "branches", "branch-a", "branch-b"))
                .node(LlmNodeDefinition.of("branch-a", "prompt-a", "out_a")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .node(LlmNodeDefinition.of("branch-b", "prompt-b", "out_b")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .slot("question", SlotType.STRING)
                .slot("branches", SlotType.OBJECT)
                .build();
        Engine engine = EngineBuilder.create()
                .workflow(workflow)
                .prompt(PromptDefinition.template("prompt-a", "分支A：{{slots.question}}"))
                .prompt(PromptDefinition.template("prompt-b", "分支B：{{slots.question}}"))
                .agent(AgentDefinition.builder("parallel-agent").workflow("parallel-wf")
                        .model("echo", "m").build())
                .defaultModelProvider("echo")
                .build();

        RunResult result = engine.run("parallel-agent", Input.of("并行提问").withSlot("question", "并行提问"));

        assertEquals(SessionState.COMPLETED, result.state());
        Object branches = result.slots().get("branches");
        assertInstanceOf(Map.class, branches);
        @SuppressWarnings("unchecked")
        Map<String, Object> joined = (Map<String, Object>) branches;
        assertEquals(2, joined.size());
        assertTrue(joined.containsKey("branch-a"));
        assertTrue(joined.containsKey("branch-b"));
    }

    @Test
    @DisplayName("子工作流节点在同一会话内运行并回写结果")
    void subWorkflowNode() {
        WorkflowDefinition child = WorkflowBuilder.create("child-wf", "1.0.0")
                .node(LlmNodeDefinition.of("child-plan", "child-prompt", "child_out")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .slot("question", SlotType.STRING)
                .slot("child_out", SlotType.STRING)
                .build();
        WorkflowDefinition parent = WorkflowBuilder.create("parent-wf", "1.0.0")
                .node(SubWorkflowNodeDefinition.of("call-child", "child-wf", "child_result")
                        .withInput("question", "slots.question"))
                .slot("question", SlotType.STRING)
                .slot("child_result", SlotType.STRING)
                .build();
        ScriptedModelProvider model = new ScriptedModelProvider("scripted").enqueueText("子工作流结论");
        Engine engine = EngineBuilder.create()
                .workflow(child)
                .workflow(parent)
                .prompt(PromptDefinition.template("child-prompt", "子问题：{{slots.question}}"))
                .modelProvider(model)
                .agent(AgentDefinition.builder("parent-agent").workflow("parent-wf")
                        .model("scripted", "m").build())
                .build();

        RunResult result = engine.run("parent-agent", Input.of("提问").withSlot("question", "子问题内容"));

        assertEquals(SessionState.COMPLETED, result.state());
        assertEquals("子工作流结论", result.slots().get("child_result"));
        assertTrue(result.slots().containsKey("child_result_slots"));
    }

    /**
     * 构建测试引擎。
     *
     * @param agent    Agent 定义
     * @param workflow 工作流
     * @param model    脚本化模型
     * @return 引擎实例
     */
    private Engine engine(AgentDefinition agent, WorkflowDefinition workflow, ScriptedModelProvider model) {
        events = new InMemoryEventBus();
        metrics = new InMemoryMetrics();
        exporter = new InMemorySpanExporter();
        return EngineBuilder.create()
                .workflow(workflow)
                .prompt(EngineFixture.planPrompt())
                .prompt(EngineFixture.reportPrompt())
                .tool(EngineFixture.calculator())
                .modelProvider(model)
                .agent(agent)
                .events(events)
                .metrics(metrics)
                .tracer(new SimpleTracer(true, null, List.of(exporter)))
                .build();
    }
}
