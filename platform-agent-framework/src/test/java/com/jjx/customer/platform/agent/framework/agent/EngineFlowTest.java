package com.jjx.customer.platform.agent.framework.agent;

import com.jjx.customer.platform.agent.framework.agent.Agent;
import com.jjx.customer.platform.agent.framework.capability.AgentCapability;
import com.jjx.customer.platform.agent.framework.result.ContextArtifact;
import com.jjx.customer.platform.agent.framework.result.ExecutionResult;
import com.jjx.customer.platform.agent.framework.result.OutcomeKind;
import com.jjx.customer.platform.agent.framework.tool.ToolResult;
import com.jjx.customer.platform.agent.framework.workflow.WorkflowErrorPolicy;
import com.jjx.customer.platform.agent.framework.node.NodeKind;
import com.jjx.customer.platform.agent.framework.workflow.SlotSpec;
import com.jjx.customer.platform.agent.framework.workflow.WorkflowStageSpec;
import com.jjx.customer.platform.agent.framework.workflow.Workflow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineFlowTest {

    private static final ContextArtifact ART_1 =
            new ContextArtifact(1, "片段一", "doc1.md", 0.9, "VECTOR", Map.of("locator", "第 1 节"));
    private static final ContextArtifact ART_2 =
            new ContextArtifact(2, "片段二", "doc2.md", 0.8, "KEYWORD", Map.of());

    @Test
    void 确定性节点_流式能力_产出上下文与引用_元数据按时序流出() {
        Workflow workflow = TestFixtures.workflow("wf_rag", List.of(),
                List.of(new WorkflowStageSpec("retrieve", NodeKind.DETERMINISTIC, "wf_rag/retrieve",
                        List.of("retrieve_knowledge"), null)),
                Set.of(AgentCapability.STREAMING, AgentCapability.CITATIONS,
                        AgentCapability.RETRIEVAL_METRICS),
                "wf_rag/answer");
        Agent agent = TestFixtures.agent("knowledge", "knowledge",
                Set.of(AgentCapability.STREAMING, AgentCapability.CITATIONS,
                        AgentCapability.RETRIEVAL_METRICS), workflow);
        WorkflowEngine engine = TestFixtures.single(agent,
                List.of(TestFixtures.ext("retrieve_knowledge",
                        ToolResult.ok("命中 2 条", List.of(ART_1, ART_2)))),
                null, Map.of("wf_rag/answer", "你是知识助手", "wf_rag/retrieve", "检索阶段"));

        TestFixtures.RecordingSink sink = new TestFixtures.RecordingSink();
        ExecutionResult result = engine.execute(TestFixtures.request("问题"), sink);

        assertEquals(OutcomeKind.WITH_CONTEXT, result.kind());
        assertNotNull(result.generation());
        assertEquals("你是知识助手", result.generation().answerPromptContent());
        assertTrue(result.generation().assembledContextText().contains("[ref=1]"));
        assertEquals(2, result.citations().citations().size());
        assertEquals(2, result.retrievalStats().returned());
        assertEquals("hash-1", result.fingerprint().promptHash());
        assertEquals("knowledge", result.fingerprint().agentId());
        assertEquals(List.of("fingerprint", "context", "citations", "stats", "trace"), sink.events);
        assertTrue(result.trace().steps().stream().anyMatch(s -> s.action().equals("retrieve")));
    }

    @Test
    void 无流式能力_直出末节点文本() {
        Workflow workflow = TestFixtures.workflow("wf_ops", List.of(),
                List.of(new WorkflowStageSpec("verify", NodeKind.DETERMINISTIC, "wf_ops/verify",
                        List.of("validate"), null)),
                Set.of(), null);
        Agent agent = TestFixtures.agent("ops", "ops", Set.of(), workflow);
        WorkflowEngine engine = TestFixtures.single(agent,
                List.of(TestFixtures.ext("validate", ToolResult.ok("结论：报文合法"))),
                null, Map.of("wf_ops/verify", "校验阶段"));

        ExecutionResult result = engine.execute(TestFixtures.request("帮我校验"));

        assertEquals(OutcomeKind.DIRECT, result.kind());
        assertEquals("结论：报文合法", result.text());
    }

    @Test
    void 缺必填槽位_一次问齐返回澄清() {
        Workflow workflow = TestFixtures.workflow("wf_slots",
                List.of(new SlotSpec("trace_id", true, "请提供 traceId", "日志详情页可复制")),
                List.of(), Set.of(), null);
        Agent agent = TestFixtures.agent("ops", "ops", Set.of(), workflow);
        WorkflowEngine engine = TestFixtures.single(agent, List.of(), null, Map.of());

        ExecutionResult result = engine.execute(TestFixtures.request("帮我看看"));

        assertEquals(OutcomeKind.CLARIFY, result.kind());
        assertTrue(result.text().contains("请提供 traceId"));
    }

    @Test
    void 阶段失败_默认策略_记录失败并继续后续阶段() {
        Workflow workflow = TestFixtures.workflow("wf_a", List.of(),
                List.of(new WorkflowStageSpec("bad", NodeKind.DETERMINISTIC, "wf_a/bad",
                                List.of("boom"), null),
                        new WorkflowStageSpec("good", NodeKind.DETERMINISTIC, "wf_a/good",
                                List.of("echo"), null)),
                Set.of(), null);
        Agent agent = TestFixtures.agent("a", "a", Set.of(), workflow);
        WorkflowEngine engine = TestFixtures.single(agent,
                List.of(TestFixtures.throwingExt("boom"), TestFixtures.ext("echo", ToolResult.ok("后续结论"))),
                null, Map.of("wf_a/bad", "坏阶段", "wf_a/good", "好阶段"));

        ExecutionResult result = engine.execute(TestFixtures.request("问题"));

        assertEquals("后续结论", result.text());
        assertTrue(result.trace().steps().stream().anyMatch(s -> s.status().equals("FAILED")));
    }

    @Test
    void 阶段失败_升级策略_产出升级结果() {
        WorkflowStageSpec stage = new WorkflowStageSpec("bad", NodeKind.DETERMINISTIC, "wf_b/bad",
                List.of("boom"), null, 1, null, WorkflowErrorPolicy.escalate(), null);
        Workflow workflow = TestFixtures.workflow("wf_b", List.of(), List.of(stage), Set.of(), null);
        Agent agent = TestFixtures.agent("b", "b", Set.of(), workflow);
        WorkflowEngine engine = TestFixtures.single(agent,
                List.of(TestFixtures.throwingExt("boom")), null, Map.of("wf_b/bad", "坏阶段"));

        ExecutionResult result = engine.execute(TestFixtures.request("问题"));

        assertEquals(OutcomeKind.ESCALATE, result.kind());
        assertTrue(result.text().contains("boom"));
    }
}
