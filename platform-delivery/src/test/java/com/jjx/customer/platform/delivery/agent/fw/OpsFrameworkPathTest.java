package com.jjx.customer.platform.business;
import com.jjx.customer.platform.prompt.service.PromptBindingService;
import com.jjx.customer.platform.prompt.snapshot.PromptStoreSnapshotSource;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.jjx.customer.platform.business.agents.OpsDiagnoseFrameworkAgent;
import com.jjx.customer.platform.business.workflows.OpsDiagnoseWorkflow;

import com.jjx.customer.platform.agent.framework.agent.Agent;
import com.jjx.customer.platform.agent.framework.agent.AgentRequest;
import com.jjx.customer.platform.agent.framework.result.ExecutionResult;
import com.jjx.customer.platform.agent.framework.result.OutcomeKind;
import com.jjx.customer.platform.agent.framework.tool.ToolInvocation;
import com.jjx.customer.platform.agent.framework.tool.ToolResult;
import com.jjx.customer.platform.agent.framework.workflow.Workflow;
import com.jjx.customer.platform.agent.framework.agent.ExecutionPlanner;
import com.jjx.customer.platform.agent.framework.agent.WorkflowEngine;
import com.jjx.customer.platform.agent.framework.agent.AgentWorkflowBindingResolver;
import com.jjx.customer.platform.agent.framework.capability.CapabilityConfig;
import com.jjx.customer.platform.agent.framework.workflow.DefaultWorkflowDriver;
import com.jjx.customer.platform.agent.framework.model.ModelPort;
import com.jjx.customer.platform.agent.framework.model.ModelReply;
import com.jjx.customer.platform.agent.framework.node.DeterministicNodeExecutor;
import com.jjx.customer.platform.agent.framework.node.LoopNodeExecutor;
import com.jjx.customer.platform.agent.framework.node.NodeExecutorRegistry;
import com.jjx.customer.platform.agent.framework.agent.AgentRegistry;
import com.jjx.customer.platform.agent.framework.workflow.InMemoryWorkflowCatalog;
import com.jjx.customer.platform.agent.framework.route.RouteTable;
import com.jjx.customer.platform.agent.framework.tool.ToolRegistry;
import com.jjx.customer.platform.agent.framework.tool.control.AskUserBaseTool;
import com.jjx.customer.platform.agent.framework.tool.control.FinishBaseTool;
import com.jjx.customer.platform.observe.tools.OpsQueryLogsTool;
import com.jjx.customer.platform.business.tools.ops.OpsSlotSpecs;
import com.jjx.customer.platform.business.tools.ops.OpsValidateRequestTool;
import com.jjx.customer.platform.config.prompt.PromptStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ops 重建验证：真实三阶段 workflow 走框架引擎，覆盖槽位追问 / finish 出环 / ask_user 转澄清。
 */
class OpsFrameworkPathTest {

    private static com.jjx.customer.platform.agent.framework.tool.ExtensionTool stub(String name, ToolResult result) {
        return new com.jjx.customer.platform.agent.framework.tool.ExtensionTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "测试桩 " + name;
            }

            @Override
            public String inputSchema() {
                return "{\"type\":\"object\"}";
            }

            @Override
            public ToolResult execute(ToolInvocation invocation) {
                return result;
            }
        };
    }

    /** 脚本化模型：按顺序返回预设回复。 */
    private static ModelPort scripted(List<ModelReply> replies) {
        List<ModelReply> queue = new ArrayList<>(replies);
        return request -> queue.isEmpty() ? new ModelReply("（无更多脚本）", List.of()) : queue.remove(0);
    }

    private static WorkflowEngine engine(ModelPort modelPort) {
        OpsDiagnoseWorkflow workflow = new OpsDiagnoseWorkflow();
        Agent agent = new OpsDiagnoseFrameworkAgent(workflow);
        ToolRegistry tools = new ToolRegistry(List.of(
                stub("query_logs", ToolResult.ok("命中 3 条日志")),
                stub("retrieve_knowledge", ToolResult.ok("命中 2 条资料")),
                stub("validate_request", ToolResult.ok("✅ 校验通过")),
                new AskUserBaseTool(),
                new FinishBaseTool()));
        NodeExecutorRegistry executors = new NodeExecutorRegistry(List.of(
                new DeterministicNodeExecutor(tools),
                new LoopNodeExecutor(modelPort, tools)));
        ExecutionPlanner planner = new ExecutionPlanner(
                new RouteTable(List.of(new OpsIntentRouteStrategy((OpsDiagnoseFrameworkAgent) agent))),
                new InMemoryWorkflowCatalog(List.of((Workflow) workflow)),
                AgentWorkflowBindingResolver.DEFAULT, CapabilityConfig.DEFAULT,
                new PromptStoreSnapshotSource(new PromptStore(), noBindings()));
        return new WorkflowEngine(planner, new DefaultWorkflowDriver(executors),
                new AgentRegistry(List.of(agent)), 3);
    }

    private static AgentRequest fullSlots(String question) {
        return new AgentRequest(question, Map.of(
                AgentRequest.ATTR_INTENT_DOMAIN, OpsDiagnoseFrameworkAgent.INTENT_DOMAIN,
                OpsSlotSpecs.ENVIRONMENT, "prod",
                OpsSlotSpecs.INTERFACE, "/api/invoice/reverse",
                OpsSlotSpecs.TIME, "2026-09-12 10:00",
                OpsSlotSpecs.ERROR, "500 冲红失败",
                OpsSlotSpecs.PAYLOAD, "{\"invoiceNo\":\"123\"}"));
    }

    @Test
    void 缺必填槽位_一次问齐并转澄清() {
        WorkflowEngine engine = engine(scripted(List.of()));

        ExecutionResult result = engine.execute(new AgentRequest("冲红报错了",
                Map.of(AgentRequest.ATTR_INTENT_DOMAIN, OpsDiagnoseFrameworkAgent.INTENT_DOMAIN)));

        assertEquals(OutcomeKind.CLARIFY, result.kind());
        assertTrue(result.text().contains("环境是正式还是测试？"), "应包含槽位追问话术");
        assertTrue(result.text().contains("报错信息是什么？"), "一次问齐：多个缺失项合并");
    }

    @Test
    void 模型调用finish_出环并以终稿直答() {
        // 每个阶段都以 finish 出环（末阶段终稿即最终答复）
        ModelPort model = request -> new ModelReply(null, List.of(
                new com.jjx.customer.platform.agent.framework.model.ToolCall("finish",
                        Map.of("answer", "结论：报文缺少 invoiceNo，已给出修正版。"))));
        WorkflowEngine engine = engine(model);

        ExecutionResult result = engine.execute(fullSlots("冲红报错了"));

        assertEquals(OutcomeKind.DIRECT, result.kind());
        assertTrue(result.text().contains("已给出修正版"));
        assertEquals("ops_diagnose", result.fingerprint().agentId());
        assertEquals(OpsDiagnoseWorkflow.ID, result.fingerprint().workflowId());
    }

    @Test
    void 模型调用ask_user_中断转澄清() {
        ModelPort model = scripted(List.of(new ModelReply(null, List.of(
                new com.jjx.customer.platform.agent.framework.model.ToolCall("ask_user",
                        Map.of("question", "请提供完整的请求报文"))))));
        WorkflowEngine engine = engine(model);

        ExecutionResult result = engine.execute(fullSlots("冲红报错了"));

        assertEquals(OutcomeKind.CLARIFY, result.kind());
        assertEquals("请提供完整的请求报文", result.text());
    }

    @Test
    void 槽位目录声明完整_前三阶段与工具白名单齐备() {
        OpsDiagnoseWorkflow workflow = new OpsDiagnoseWorkflow();

        assertEquals(7, workflow.slots().size());
        assertEquals(3, workflow.stages().size());
        assertTrue(workflow.stages().get(0).extensionTools().contains(OpsQueryLogsTool.NAME));
        assertTrue(workflow.stages().get(1).extensionTools().contains(OpsValidateRequestTool.NAME));
        assertTrue(workflow.stages().get(2).extensionTools().contains(OpsValidateRequestTool.NAME));
        // ask_user / finish 是 BaseTool：不进白名单，由引擎注入
        assertTrue(workflow.stages().stream()
                .noneMatch(s -> s.extensionTools().contains(FinishBaseTool.NAME)));
    }
    /** 无绑定 stub：overridesFor 返回空 map（快照层回退 classpath，等价接线前行为）。 */
    private static PromptBindingService noBindings() {
        PromptBindingService m = mock(PromptBindingService.class);
        when(m.overridesFor(anyString())).thenReturn(java.util.Map.of());
        return m;
    }
}
