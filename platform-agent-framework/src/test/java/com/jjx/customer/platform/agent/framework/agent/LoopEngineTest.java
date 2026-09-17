package com.jjx.customer.platform.agent.framework.agent;

import com.jjx.customer.platform.agent.framework.agent.Agent;
import com.jjx.customer.platform.agent.framework.result.ExecutionResult;
import com.jjx.customer.platform.agent.framework.tool.ToolResult;
import com.jjx.customer.platform.agent.framework.node.NodeKind;
import com.jjx.customer.platform.agent.framework.workflow.WorkflowStageSpec;
import com.jjx.customer.platform.agent.framework.workflow.Workflow;
import com.jjx.customer.platform.agent.framework.model.ModelPort;
import com.jjx.customer.platform.agent.framework.model.ModelReply;
import com.jjx.customer.platform.agent.framework.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LoopEngineTest {

    @Test
    void 工具循环_先调工具再出终稿() {
        AtomicInteger modelCalls = new AtomicInteger();
        ModelPort modelPort = request -> modelCalls.getAndIncrement() == 0
                ? new ModelReply(null, List.of(new ToolCall("retrieve_knowledge", Map.of("query", "发票"))))
                : new ModelReply("最终答案", List.of());

        Workflow workflow = TestFixtures.workflow("wf_loop", List.of(),
                List.of(new WorkflowStageSpec("loop", NodeKind.LOOP, "wf_loop/loop",
                        List.of("retrieve_knowledge"), null)),
                Set.of(), null);
        Agent agent = TestFixtures.agent("react", "react", Set.of(), workflow);
        WorkflowEngine engine = TestFixtures.single(agent,
                List.of(TestFixtures.ext("retrieve_knowledge", ToolResult.ok("命中 3 条"))),
                modelPort, Map.of("wf_loop/loop", "你是工具循环助手"));

        ExecutionResult result = engine.execute(TestFixtures.request("发票怎么冲红"));

        assertEquals("最终答案", result.text());
        assertEquals(2, modelCalls.get());
        assertEquals(2, result.trace().llmCalls());
        assertEquals(1, result.trace().toolCalls());
    }
}
