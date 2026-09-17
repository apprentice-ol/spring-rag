package com.jjx.customer.platform.agent.framework.agent;

import com.jjx.customer.platform.agent.framework.agent.Agent;
import com.jjx.customer.platform.agent.framework.result.ExecutionResult;
import com.jjx.customer.platform.agent.framework.result.OutcomeKind;
import com.jjx.customer.platform.agent.framework.tool.ToolResult;
import com.jjx.customer.platform.agent.framework.workflow.WorkflowErrorPolicy;
import com.jjx.customer.platform.agent.framework.node.NodeKind;
import com.jjx.customer.platform.agent.framework.workflow.WorkflowStageSpec;
import com.jjx.customer.platform.agent.framework.workflow.Workflow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCallEngineTest {

    private static WorkflowStageSpec callStage(String name, String target, WorkflowErrorPolicy policy) {
        return new WorkflowStageSpec(name, NodeKind.AGENT_CALL, null, List.of(), null,
                1, null, policy, target);
    }

    @Test
    void 主agent调子agent_结论上卷() {
        Workflow childWorkflow = TestFixtures.workflow("wf_child", List.of(),
                List.of(new WorkflowStageSpec("echo", NodeKind.DETERMINISTIC, "wf_child/echo",
                        List.of("echo"), null)),
                Set.of(), null);
        Agent child = TestFixtures.agent("child", "child", Set.of(), childWorkflow);

        Workflow parentWorkflow = TestFixtures.workflow("wf_parent", List.of(),
                List.of(callStage("call_child", "child", WorkflowErrorPolicy.AS_IS)), Set.of(), null);
        Agent parent = TestFixtures.agent("parent", "parent", Set.of(), parentWorkflow);

        WorkflowEngine engine = TestFixtures.engine(List.of(parent, child),
                List.of(TestFixtures.ext("echo", ToolResult.ok("子结论"))),
                null, Map.of("wf_child/echo", "子阶段"));

        ExecutionResult result = engine.execute(TestFixtures.request("问题"));

        assertEquals(OutcomeKind.DIRECT, result.kind());
        assertEquals("子结论", result.text());
    }

    @Test
    void 递归调用成环_环检测拒绝并按策略升级() {
        Workflow childWorkflow = TestFixtures.workflow("wf_child", List.of(),
                List.of(callStage("call_parent", "parent", WorkflowErrorPolicy.escalate())), Set.of(), null);
        Agent child = TestFixtures.agent("child", "child", Set.of(), childWorkflow);

        Workflow parentWorkflow = TestFixtures.workflow("wf_parent", List.of(),
                List.of(callStage("call_child", "child", WorkflowErrorPolicy.escalate())), Set.of(), null);
        Agent parent = TestFixtures.agent("parent", "parent", Set.of(), parentWorkflow);

        WorkflowEngine engine = TestFixtures.engine(List.of(parent, child), List.of(), null, Map.of());

        ExecutionResult result = engine.execute(TestFixtures.request("问题"));

        // A→B→A 由调用链环检测直接拒绝（无需真实执行到深度上限撞墙）
        assertEquals(OutcomeKind.ESCALATE, result.kind());
        assertTrue(result.text().contains("成环"));
    }

    @Test
    void 子agent消耗上卷父总账() {
        // 子流程每次 DETERMINISTIC 执行经模型端口记 0 次 LLM；改用 LOOP 节点让子真实消耗 2 次模型调用
        com.jjx.customer.platform.agent.framework.model.ModelPort twoCallPort = request -> {
            // 第一次返回工具调用，第二次返回终稿
            if (request.observations().isEmpty()) {
                return new com.jjx.customer.platform.agent.framework.model.ModelReply(null,
                        List.of(new com.jjx.customer.platform.agent.framework.model.ToolCall(
                                "echo", Map.of())));
            }
            return new com.jjx.customer.platform.agent.framework.model.ModelReply("子结论", List.of());
        };
        Workflow childWorkflow = TestFixtures.workflow("wf_child", List.of(),
                List.of(new WorkflowStageSpec("child_loop", NodeKind.LOOP, "wf_child/loop", List.of(), null)),
                Set.of(), null);
        Agent child = TestFixtures.agent("child", "child", Set.of(), childWorkflow);

        Workflow parentWorkflow = TestFixtures.workflow("wf_parent", List.of(),
                List.of(callStage("call_child", "child", WorkflowErrorPolicy.AS_IS)), Set.of(), null);
        Agent parent = TestFixtures.agent("parent", "parent", Set.of(), parentWorkflow);

        WorkflowEngine engine = TestFixtures.engine(List.of(parent, child),
                List.of(TestFixtures.ext("echo", ToolResult.ok("ok")), TestFixtures.base("finish")),
                twoCallPort, Map.of("wf_child/loop", "子阶段prompt"));

        ExecutionResult result = engine.execute(TestFixtures.request("问题"));

        assertEquals(OutcomeKind.DIRECT, result.kind());
        assertEquals("子结论", result.text());
        // 子 2 次 LLM 消耗上卷父总账（父自身 0 次）
        assertEquals(2, result.trace().llmCalls());
        // 子轨迹嵌套在父的 AGENT_CALL 步骤下
        assertEquals(1, result.trace().steps().stream()
                .filter(s -> "call_child".equals(s.action()) && !s.children().isEmpty()).count());
    }
}
