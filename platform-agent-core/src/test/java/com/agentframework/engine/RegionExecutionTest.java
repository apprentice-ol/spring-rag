package com.agentframework.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentframework.crosscutting.guard.Guard;
import com.agentframework.crosscutting.guard.GuardContext;
import com.agentframework.crosscutting.guard.GuardDecision;
import com.agentframework.definition.agent.AgentDefinition;
import com.agentframework.definition.node.LlmNodeDefinition;
import com.agentframework.definition.node.NodeMeta;
import com.agentframework.definition.node.HumanNodeDefinition;
import com.agentframework.definition.policy.QuotaPolicy;
import com.agentframework.definition.prompt.PromptDefinition;
import com.agentframework.definition.region.Paradigm;
import com.agentframework.definition.region.RegionDefinition;
import com.agentframework.definition.region.RegionPolicy;
import com.agentframework.definition.workflow.SlotType;
import com.agentframework.definition.workflow.WorkflowBuilder;
import com.agentframework.definition.workflow.WorkflowDefinition;
import com.agentframework.engine.core.Engine;
import com.agentframework.engine.core.EngineBuilder;
import com.agentframework.engine.core.RunResult;
import com.agentframework.engine.policy.Activation;
import com.agentframework.engine.policy.RegionMetrics;
import com.agentframework.infra.modelgateway.EchoModelProvider;
import com.agentframework.runtime.session.Input;
import com.agentframework.runtime.session.SessionState;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Region 执行接入测试：作用域、配额、指标与无策略时的零影响。 */
class RegionExecutionTest {

    @Test
    @DisplayName("Region 策略只作用于区域内节点")
    void regionPolicyAppliesOnlyInsideRegion() {
        RecordingGuard guard = new RecordingGuard("region-guard");
        WorkflowDefinition workflow = WorkflowBuilder.create("region-policy-wf", "1.0.0")
                .node(LlmNodeDefinition.of("a", "prompt-a", "out_a"))
                .node(LlmNodeDefinition.of("b", "prompt-b", "out_b")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .edge("a", "b")
                .slot("out_a", SlotType.STRING)
                .slot("out_b", SlotType.STRING)
                .region(RegionDefinition.of("r1", Paradigm.ROUTER, "a")
                        .withPolicy(new RegionPolicy(RegionPolicy.bindings("region-guard"), null, null, null)))
                .build();
        Engine engine = EngineBuilder.create()
                .workflow(workflow)
                .prompt(PromptDefinition.template("prompt-a", "A"))
                .prompt(PromptDefinition.template("prompt-b", "B"))
                .modelProvider(new EchoModelProvider("echo", request -> "ok"))
                .defaultModelProvider("echo")
                .agent(agent("region-policy-agent", "region-policy-wf"))
                .component("region-guard", Activation.DEFAULT_OFF, guard)
                .build();

        RunResult result = engine.run("region-policy-agent", Input.of("你好"));

        assertEquals(SessionState.COMPLETED, result.state(), () -> "实际错误：" + result.error());
        assertTrue(guard.nodes.contains("a"), () -> "实际节点：" + guard.nodes);
        assertFalse(guard.nodes.contains("b"), () -> "节点 b 不属于 r1：" + guard.nodes);
    }

    @Test
    @DisplayName("Region 配额触发时异常中标注区域")
    void regionQuotaIsEnforcedWithRegionLabel() {
        WorkflowDefinition workflow = WorkflowBuilder.create("region-quota-wf", "1.0.0")
                .node(LlmNodeDefinition.of("hello", "hello-prompt", "greeting")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .slot("greeting", SlotType.STRING)
                .region(RegionDefinition.of("r1", Paradigm.ROUTER, "hello")
                        .withPolicy(new RegionPolicy(null, null, null, QuotaPolicy.of(1, 50, 50))))
                .build();
        Engine engine = engine(workflow, "region-quota-agent");

        RunResult result = engine.run("region-quota-agent", Input.of("你好"));

        assertEquals(SessionState.FAILED, result.state());
        assertTrue(result.error().contains("区域 'r1'"), () -> "实际错误：" + result.error());
        assertTrue(result.error().contains("tokens"), () -> "实际错误：" + result.error());
    }

    @Test
    @DisplayName("Region 指标按会话聚合，未声明 Region 的工作流为空表")
    void regionMetricsAreAggregatedPerSession() {
        WorkflowDefinition workflow = WorkflowBuilder.create("region-metrics-wf", "1.0.0")
                .node(LlmNodeDefinition.of("a", "prompt-a", "out_a"))
                .node(LlmNodeDefinition.of("b", "prompt-b", "out_b")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .edge("a", "b")
                .slot("out_a", SlotType.STRING)
                .slot("out_b", SlotType.STRING)
                .region(RegionDefinition.of("r1", Paradigm.ROUTER, "a", "b"))
                .build();
        Engine engine = engine(workflow, "region-metrics-agent");

        RunResult result = engine.run("region-metrics-agent", Input.of("你好"));

        assertEquals(SessionState.COMPLETED, result.state(), () -> "实际错误：" + result.error());
        Map<String, RegionMetrics> metrics = engine.regionMetrics(result.sessionId());
        assertEquals(1, metrics.size());
        RegionMetrics regionMetrics = metrics.get("r1");
        assertEquals(2, regionMetrics.nodeCount());
        assertEquals(2, regionMetrics.llmCalls());
        assertEquals("ROUTER", regionMetrics.paradigm());
        assertTrue(regionMetrics.tokenCount() > 0, () -> "实际 token：" + regionMetrics.tokenCount());
        assertTrue(regionMetrics.executionTimeMs() >= 0);

        Engine plainEngine = engine(plainWorkflow(), "plain-agent");
        RunResult plain = plainEngine.run("plain-agent", Input.of("你好"));

        assertTrue(plainEngine.regionMetrics(plain.sessionId()).isEmpty());
    }

    @Test
    @DisplayName("声明无策略的 Region 不改变执行结果")
    void regionWithoutPolicyDoesNotChangeExecution() {
        Engine plainEngine = engine(plainWorkflow(), "plain-agent");
        Engine regionEngine = engine(WorkflowBuilder.create("plain-wf", "1.0.0")
                .node(LlmNodeDefinition.of("hello", "hello-prompt", "greeting")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .slot("greeting", SlotType.STRING)
                .region(RegionDefinition.of("r1", Paradigm.ROUTER, "hello"))
                .build(), "region-only-agent");

        RunResult plain = plainEngine.run("plain-agent", Input.of("你好"));
        RunResult withRegion = regionEngine.run("region-only-agent", Input.of("你好"));

        assertEquals(SessionState.COMPLETED, withRegion.state(), () -> "实际错误：" + withRegion.error());
        assertEquals(plain.visitedNodes(), withRegion.visitedNodes());
        assertEquals(plain.output(), withRegion.output());
        assertEquals(plain.slots(), withRegion.slots());
    }

    @Test
    @DisplayName("人工节点挂起计入 Region 的审批指标")
    void humanApprovalMetricsAreAggregated() {
        WorkflowDefinition workflow = WorkflowBuilder.create("approval-region-wf", "1.0.0")
                .node(new HumanNodeDefinition("approve", "请确认", "approval", "approval", null, null,
                        NodeMeta.empty().withAttribute("terminal", true)))
                .slot("approval", SlotType.STRING)
                .region(RegionDefinition.of("r4_human_loop", Paradigm.HUMAN_IN_LOOP, "approve"))
                .build();
        Engine engine = engine(workflow, "approval-region-agent");

        RunResult result = engine.run("approval-region-agent", Input.of("请审批"));

        assertEquals(SessionState.SUSPENDED, result.state());
        RegionMetrics metrics = engine.regionMetrics(result.sessionId()).get("r4_human_loop");
        assertEquals(1, metrics.approvalCount());
        assertEquals(1, metrics.approvalPendingCount());
        assertEquals("HUMAN_IN_LOOP", metrics.paradigm());
    }

    /**
     * @return 不含 Region 的单节点工作流
     */
    private WorkflowDefinition plainWorkflow() {
        return WorkflowBuilder.create("plain-wf", "1.0.0")
                .node(LlmNodeDefinition.of("hello", "hello-prompt", "greeting")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .slot("greeting", SlotType.STRING)
                .build();
    }

    /**
     * @param workflow 工作流
     * @param agentId  Agent id
     * @return 使用回声模型的引擎
     */
    private Engine engine(WorkflowDefinition workflow, String agentId) {
        return EngineBuilder.create()
                .workflow(workflow)
                .prompt(PromptDefinition.template("hello-prompt", "问候：{{messages}}"))
                .prompt(PromptDefinition.template("prompt-a", "A"))
                .prompt(PromptDefinition.template("prompt-b", "B"))
                .modelProvider(new EchoModelProvider("echo", request -> "你好"))
                .defaultModelProvider("echo")
                .agent(agent(agentId, workflow.id()))
                .build();
    }

    /**
     * @param id         Agent id
     * @param workflowId 工作流 id
     * @return Agent 定义
     */
    private AgentDefinition agent(String id, String workflowId) {
        return AgentDefinition.builder(id).workflow(workflowId).model("echo", "echo").build();
    }

    /** 记录所属节点的守卫。 */
    private static final class RecordingGuard implements Guard {

        private final String name;
        private final List<String> nodes = new CopyOnWriteArrayList<>();

        RecordingGuard(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public GuardDecision check(GuardContext context) {
            if (context.nodeId() != null) {
                nodes.add(context.nodeId());
            }
            return GuardDecision.allow();
        }
    }
}
