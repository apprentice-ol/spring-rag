package com.jjx.customer.platform.agent.framework.agent;

import com.jjx.customer.platform.agent.framework.agent.Agent;
import com.jjx.customer.platform.agent.framework.agent.AgentRequest;
import com.jjx.customer.platform.agent.framework.plan.ExecutionPlan;
import com.jjx.customer.platform.agent.framework.prompt.PromptSnapshot;
import com.jjx.customer.platform.agent.framework.result.ExecutionResult;
import com.jjx.customer.platform.agent.framework.result.MetadataContributor;
import com.jjx.customer.platform.agent.framework.result.OutcomeKind;
import com.jjx.customer.platform.agent.framework.tool.ToolResult;
import com.jjx.customer.platform.agent.framework.node.NodeKind;
import com.jjx.customer.platform.agent.framework.workflow.SlotSpec;
import com.jjx.customer.platform.agent.framework.workflow.WorkflowStageSpec;
import com.jjx.customer.platform.agent.framework.workflow.Workflow;
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
import com.jjx.customer.platform.agent.framework.route.RouteContext;
import com.jjx.customer.platform.agent.framework.route.RouteDecision;
import com.jjx.customer.platform.agent.framework.route.RouteStrategy;
import com.jjx.customer.platform.agent.framework.route.RouteTable;
import com.jjx.customer.platform.agent.framework.tool.ToolRegistry;
import com.jjx.customer.platform.agent.framework.tool.control.AskUserBaseTool;
import com.jjx.customer.platform.agent.framework.tool.control.EscalateBaseTool;
import com.jjx.customer.platform.agent.framework.tool.control.FinishBaseTool;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批次 A 补齐行为的专项测试：槽位抽槽与归一、replan 三态裁决、
 * escalate BaseTool、执行拦截器、MetadataContributor、预算切分。
 */
class DriverAdvancedTest {

    // ==================== 槽位：抽槽 + 归一 ====================

    @Test
    void 抽槽_从用户消息填空缺槽位_已确认值优先() {
        // 预填 interface（已确认），用户消息含环境 → 抽槽只填空缺
        Workflow workflow = new Workflow() {
            @Override
            public String id() {
                return "wf";
            }

            @Override
            public List<SlotSpec> slots() {
                return List.of(
                        new SlotSpec("environment", true, "环境？", null, null, "环境：prod/test/dev/uat"),
                        new SlotSpec("interface", true, "接口？", null, null, null));
            }

            @Override
            public List<WorkflowStageSpec> stages() {
                return List.of(new WorkflowStageSpec("run", NodeKind.DETERMINISTIC, "wf/run", List.of("echo"), null));
            }

            @Override
            public String slotExtractPromptKey() {
                return "wf/slot-extract";
            }
        };
        ModelPort extractor = request -> {
            assertTrue(request.system().contains("槽位目录"));
            assertTrue(request.system().contains("已确认槽位"));
            return new ModelReply("{\"environment\":\"prod\",\"interface\":null}", List.of());
        };
        WorkflowEngine engine = engine(workflow, extractor,
                Map.of("wf/run", "阶段prompt", "wf/slot-extract", "抽槽prompt"), null);

        AgentRequest request = new AgentRequest("正式环境调用超时", Map.of("interface", "/api/x"));
        ExecutionResult result = engine.execute(request);

        assertEquals(OutcomeKind.DIRECT, result.kind());
        // 抽槽 1 次 + 确定性阶段 0 次
        assertEquals(1, result.trace().llmCalls());
        assertTrue(result.trace().steps().stream().anyMatch(s -> "extract_slots".equals(s.action())));
    }

    @Test
    void 抽槽输出无法解析_按已知槽位继续不阻断() {
        Workflow workflow = workflowWithSlotExtract();
        WorkflowEngine engine = engine(workflow, request -> new ModelReply("不是 JSON", List.of()),
                Map.of("wf/run", "p", "wf/slot-extract", "抽槽"), null);

        // environment 已预填，target 槽空缺 → 抽槽触发但输出无法解析 → 按已知槽位继续（缺 target → CLARIFY）
        ExecutionResult result = engine.execute(new AgentRequest("问题", Map.of("environment", "prod")));

        assertEquals(OutcomeKind.CLARIFY, result.kind());
        assertTrue(result.trace().steps().stream()
                .anyMatch(s -> "extract_slots".equals(s.action()) && "WARN".equals(s.status())));
    }

    // ==================== replan：三态裁决 ====================

    @Test
    void replan_adjust携带要求重跑一次后继续() {
        Workflow workflow = new Workflow() {
            @Override
            public String id() {
                return "wf";
            }

            @Override
            public List<SlotSpec> slots() {
                return List.of();
            }

            @Override
            public List<WorkflowStageSpec> stages() {
                return List.of(
                        new WorkflowStageSpec("first", NodeKind.DETERMINISTIC, "wf/first", List.of("echo"), null),
                        new WorkflowStageSpec("second", NodeKind.DETERMINISTIC, "wf/second", List.of("echo"), null));
            }

            @Override
            public String replanPromptKey() {
                return "wf/replan";
            }
        };
        // 裁决序列：adjust（第一次）→ continue（第二次）
        Deque<String> verdicts = new ArrayDeque<>(List.of(
                "{\"action\":\"adjust\",\"reason\":\"r\",\"adjustment\":\"换时间窗\"}",
                "{\"action\":\"continue\"}"));
        ModelPort port = request -> request.system().contains("检查点评估器") || request.system().contains("replan")
                ? new ModelReply(verdicts.pop(), List.of())
                : new ModelReply("ok", List.of());
        WorkflowEngine engine = engine(workflow, port,
                Map.of("wf/first", "一", "wf/second", "二", "wf/replan", "你是流程的检查点评估器"), null);

        ExecutionResult result = engine.execute(TestFixtures.request("q"));

        assertEquals(OutcomeKind.DIRECT, result.kind());
        // replan 裁决 2 次（adjust + continue），确定性节点 0 次 LLM
        assertEquals(2, result.trace().llmCalls());
        assertTrue(result.trace().steps().stream()
                .anyMatch(s -> "replan".equals(s.action()) && "ADJUST".equals(s.status())));
        // first 阶段因 adjust 重跑出现两次
        assertEquals(2, result.trace().steps().stream()
                .filter(s -> "first".equals(s.action())).count());
    }

    @Test
    void replan_escalate_直接升级() {
        Workflow workflow = twoStageWithReplan();
        ModelPort port = request -> request.system().contains("评估器")
                ? new ModelReply("{\"action\":\"escalate\",\"reason\":\"缺用户信息\"}", List.of())
                : new ModelReply("ok", List.of());
        WorkflowEngine engine = engine(workflow, port,
                Map.of("wf/first", "一", "wf/second", "二", "wf/replan", "你是流程的检查点评估器"), null);

        ExecutionResult result = engine.execute(TestFixtures.request("q"));

        assertEquals(OutcomeKind.ESCALATE, result.kind());
        assertTrue(result.text().contains("缺用户信息"));
    }

    // ==================== escalate BaseTool ====================

    @Test
    void 模型主动escalate_工具信号转升级() {
        Workflow workflow = TestFixtures.workflow("wf", List.of(),
                List.of(new WorkflowStageSpec("loop", NodeKind.LOOP, "wf/loop", List.of(), null)),
                Set.of(), null);
        ModelPort port = request -> new ModelReply("{\"tool\":\"escalate\",\"args\":{\"reason\":\"超出边界\"}}",
                List.of());
        // 直接构造 ModelReply 走原生 toolCalls 路径更贴近真实端口行为
        ModelPort nativePort = request -> new ModelReply(null, List.of(
                new com.jjx.customer.platform.agent.framework.model.ToolCall("escalate",
                        Map.of("reason", "超出边界"))));
        WorkflowEngine engine = engine(workflow, nativePort,
                Map.of("wf/loop", "p"), null);

        ExecutionResult result = engine.execute(TestFixtures.request("q"));

        assertEquals(OutcomeKind.ESCALATE, result.kind());
        assertTrue(result.text().contains("超出边界"));
    }

    // ==================== 拦截器 ====================

    @Test
    void 拦截器否决_转升级且不执行流程() {
        Workflow workflow = TestFixtures.workflow("wf", List.of(),
                List.of(new WorkflowStageSpec("run", NodeKind.DETERMINISTIC, "wf/run", List.of("echo"), null)),
                Set.of(), null);
        boolean[] executed = {false};
        ModelPort port = request -> {
            executed[0] = true;
            return new ModelReply("x", List.of());
        };
        WorkflowEngine engine = engine(workflow, port, Map.of("wf/run", "p"), plan -> "降级期限流");

        ExecutionResult result = engine.execute(TestFixtures.request("q"));

        assertEquals(OutcomeKind.ESCALATE, result.kind());
        assertTrue(result.text().contains("降级期限流"));
        assertEquals(false, executed[0]);
    }

    // ==================== MetadataContributor ====================

    @Test
    void 元数据贡献者_内容进结果metadata() {
        Workflow workflow = TestFixtures.workflow("wf", List.of(),
                List.of(new WorkflowStageSpec("run", NodeKind.DETERMINISTIC, "wf/run", List.of("echo"), null)),
                Set.of(), null);
        WorkflowEngine engine = engineWithContributor(workflow,
                Map.of("wf/run", "p"));

        ExecutionResult result = engine.execute(TestFixtures.request("q"));

        assertEquals(Map.of("costCents", 42), result.metadata().get("cost"));
    }

    // ==================== 预算切分（budgetShare） ====================

    @Test
    void budgetShare_子执行LLM上限受父切分约束() {
        Workflow childWf = TestFixtures.workflow("wf_child", List.of(),
                List.of(new WorkflowStageSpec("loop", NodeKind.LOOP, "wf_child/loop", List.of(), null, 10, null, null, null)),
                Set.of(), null, 10);
        Agent child = TestFixtures.agent("child", "child", Set.of(), childWf);

        Workflow parentWf = TestFixtures.workflow("wf_parent", List.of(),
                List.of(new WorkflowStageSpec("call", NodeKind.AGENT_CALL, null, List.of(), null,
                        1, null, null, "child", 2, null, null)), Set.of(), null);
        Agent parent = TestFixtures.agent("parent", "parent", Set.of(), parentWf);

        // 模型永远想调工具 → 子受切分上限约束在预算耗尽处中断
        ModelPort port = request -> new ModelReply(null, List.of(
                new com.jjx.customer.platform.agent.framework.model.ToolCall("finish",
                        Map.of("answer", "done"))));
        // 用永不给文本的端口测上限：每步都要求工具但工具返回 finish —— 换成 echo 让循环持续
        ToolRegistry tools = new ToolRegistry(List.of(TestFixtures.ext("echo", ToolResult.ok("观察"))));
        WorkflowEngine engine = buildEngine(List.of(parent, child), tools,
                request -> new ModelReply(null, List.of(
                        new com.jjx.customer.platform.agent.framework.model.ToolCall("echo", Map.of()))),
                Map.of("wf_child/loop", "子"), null, null);

        ExecutionResult result = engine.execute(TestFixtures.request("q"));

        // budgetShare=2：子 LOOP 最多 2 次模型调用后预算耗尽 → 父侧按 AS_IS 继续 → 父无终稿 → ESCALATE
        assertEquals(OutcomeKind.ESCALATE, result.kind());
        assertTrue(result.trace().llmCalls() <= 2 + 1);
    }

    // ==================== 装配工具 ====================

    private Workflow workflowWithSlotExtract() {
        return new Workflow() {
            @Override
            public String id() {
                return "wf";
            }

            @Override
            public List<SlotSpec> slots() {
                return List.of(new SlotSpec("environment", true, "环境？", null, null, "环境说明"),
                        new SlotSpec("target", true, "目标接口？", null, null, null));
            }

            @Override
            public List<WorkflowStageSpec> stages() {
                return List.of(new WorkflowStageSpec("run", NodeKind.DETERMINISTIC, "wf/run", List.of("echo"), null));
            }

            @Override
            public String slotExtractPromptKey() {
                return "wf/slot-extract";
            }
        };
    }

    private Workflow twoStageWithReplan() {
        return new Workflow() {
            @Override
            public String id() {
                return "wf";
            }

            @Override
            public List<SlotSpec> slots() {
                return List.of();
            }

            @Override
            public List<WorkflowStageSpec> stages() {
                return List.of(
                        new WorkflowStageSpec("first", NodeKind.DETERMINISTIC, "wf/first", List.of("echo"), null),
                        new WorkflowStageSpec("second", NodeKind.DETERMINISTIC, "wf/second", List.of("echo"), null));
            }

            @Override
            public String replanPromptKey() {
                return "wf/replan";
            }
        };
    }

    private WorkflowEngine engine(Workflow workflow, ModelPort modelPort,
                                  Map<String, String> prompts, ExecutionInterceptor interceptor) {
        Agent agent = TestFixtures.agent("a", "a", Set.of(), workflow);
        return buildEngine(List.of(agent), new ToolRegistry(List.of(
                        TestFixtures.ext("echo", ToolResult.ok("回声")),
                        new FinishBaseTool(), new AskUserBaseTool(), new EscalateBaseTool())),
                modelPort, prompts, interceptor, null);
    }

    private WorkflowEngine engineWithContributor(Workflow workflow, Map<String, String> prompts) {
        Agent agent = TestFixtures.agent("a", "a", Set.of(), workflow);
        return buildEngine(List.of(agent),
                new ToolRegistry(List.of(TestFixtures.ext("echo", ToolResult.ok("回声")))),
                request -> new ModelReply("ok", List.of()), prompts, null,
                new MetadataContributor() {
                    @Override
                    public String key() {
                        return "cost";
                    }

                    @Override
                    public Map<String, Object> contribute(ExecutionPlan plan, ExecutionResult result) {
                        return Map.of("costCents", 42);
                    }
                });
    }

    private WorkflowEngine buildEngine(List<Agent> agents, ToolRegistry toolRegistry, ModelPort modelPort,
                                       Map<String, String> prompts, ExecutionInterceptor interceptor,
                                       MetadataContributor contributor) {
        Workflow workflow = agents.get(0).workflow();
        RouteStrategy strategy = new RouteStrategy() {
            @Override
            public int priority() {
                return 10;
            }

            @Override
            public Optional<RouteDecision> match(RouteContext context) {
                return Optional.of(RouteDecision.of(agents.get(0)));
            }
        };
        ExecutionPlanner planner = new ExecutionPlanner(new RouteTable(List.of(strategy)),
                new InMemoryWorkflowCatalog(agents.stream().map(Agent::workflow).distinct().toList()),
                AgentWorkflowBindingResolver.DEFAULT, CapabilityConfig.DEFAULT,
                (agent, wf) -> new PromptSnapshot(prompts, "test@r1", "hash-1", "1:1"));
        DefaultWorkflowDriver driver = new DefaultWorkflowDriver(
                new NodeExecutorRegistry(List.of(
                        new DeterministicNodeExecutor(toolRegistry),
                        new LoopNodeExecutor(modelPort, toolRegistry))),
                modelPort, contributor == null ? List.of() : List.of(contributor));
        List<ExecutionInterceptor> interceptors = interceptor == null ? List.of() : List.of(interceptor);
        return new WorkflowEngine(planner, driver, new AgentRegistry(agents), interceptors, 3);
    }
}
