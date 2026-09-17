package com.jjx.customer.platform.agent.framework.agent;

import com.jjx.customer.platform.agent.framework.agent.Agent;
import com.jjx.customer.platform.agent.framework.result.ExecutionResult;
import com.jjx.customer.platform.agent.framework.result.OutcomeKind;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BudgetEngineTest {

    @Test
    void 阶段内预算耗尽_升级收尾而不是静默截断() {
        // 模型永远只要工具、不给终稿：预算 = 1 次 LLM 调用，第二次循环前即被拦住
        ModelPort greedy = request -> new ModelReply(null,
                List.of(new ToolCall("retrieve_knowledge", Map.of())));

        Workflow workflow = TestFixtures.workflow("wf_budget", List.of(),
                List.of(new WorkflowStageSpec("loop", NodeKind.LOOP, "wf_budget/loop",
                        List.of("retrieve_knowledge"), null)),
                Set.of(), null, 1);
        Agent agent = TestFixtures.agent("a", "a", Set.of(), workflow);
        WorkflowEngine engine = TestFixtures.single(agent,
                List.of(TestFixtures.ext("retrieve_knowledge", ToolResult.ok("命中"))),
                greedy, Map.of("wf_budget/loop", "循环提示"));

        ExecutionResult result = engine.execute(TestFixtures.request("问题"));

        assertEquals(OutcomeKind.ESCALATE, result.kind());
        assertTrue(result.text().contains("预算"));
    }
}
