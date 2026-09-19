package com.agentframework.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentframework.crosscutting.guard.Guards;
import com.agentframework.definition.agent.AgentDefinition;
import com.agentframework.definition.node.HumanNodeDefinition;
import com.agentframework.definition.node.LlmNodeDefinition;
import com.agentframework.definition.node.NodeMeta;
import com.agentframework.definition.node.ToolNodeDefinition;
import com.agentframework.definition.policy.QuotaPolicy;
import com.agentframework.definition.policy.RetryPolicy;
import com.agentframework.definition.prompt.PromptDefinition;
import com.agentframework.definition.tool.ToolDefinition;
import com.agentframework.definition.tool.ToolSchema;
import com.agentframework.definition.workflow.SlotType;
import com.agentframework.definition.workflow.WorkflowBuilder;
import com.agentframework.definition.workflow.WorkflowDefinition;
import com.agentframework.engine.core.Engine;
import com.agentframework.engine.core.EngineBuilder;
import com.agentframework.engine.core.RunResult;
import com.agentframework.engine.toolexecutor.Tool;
import com.agentframework.engine.toolexecutor.ToolResult;
import com.agentframework.infra.modelgateway.ScriptedModelProvider;
import com.agentframework.infra.storage.InMemoryEventBus;
import com.agentframework.infra.storage.InMemorySessionStore;
import com.agentframework.infra.storage.InMemorySlotStore;
import com.agentframework.runtime.event.Topics;
import com.agentframework.runtime.session.Input;
import com.agentframework.runtime.session.Session;
import com.agentframework.runtime.session.SessionState;
import com.agentframework.runtime.session.StartOptions;
import com.agentframework.sdk.Tools;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 引擎韧性测试：人工挂起与恢复、守卫拒绝、重试、配额、取消与跨进程恢复。 */
class EngineResilienceTest {

    private InMemoryEventBus events;

    @Test
    @DisplayName("人工节点挂起会话，收到输入后从断点继续")
    void humanNodeSuspendsAndResumes() {
        ScriptedModelProvider model = new ScriptedModelProvider("scripted").enqueueText("草稿内容", "已发布：草稿内容");
        Engine engine = approvalEngine(model, null, null);
        Session session = engine.startSession(engine.loadAgent("approval-agent", "latest"),
                StartOptions.defaults());

        RunResult first = engine.run(session, Input.of("请起草一份通知"));

        assertEquals(SessionState.SUSPENDED, first.state());
        assertEquals("approve", first.suspendedNode());
        assertEquals("approve", session.cursor().nodeId());
        assertTrue(first.output().contains("草稿内容"));
        assertEquals(1L, events.count(Topics.SESSION_SUSPENDED));

        RunResult resumed = engine.resume(session, Input.of("同意发布").withSlot("approval", "同意"));

        assertEquals(SessionState.COMPLETED, resumed.state());
        assertEquals("已发布：草稿内容", resumed.output());
        assertEquals("同意", resumed.slots().get("approval"));
        assertEquals(1L, events.count(Topics.SESSION_RESUMED));
        assertEquals(2, model.requests().size());
    }

    @Test
    @DisplayName("守卫拒绝输入时会话失败并发布 guard.denied 事件")
    void guardDeniesInput() {
        ScriptedModelProvider model = new ScriptedModelProvider("scripted").enqueueText("不该被调用");
        InMemoryEventBus bus = new InMemoryEventBus();
        Engine engine = EngineBuilder.create()
                .workflow(EngineFixture.pipeline())
                .prompt(EngineFixture.planPrompt())
                .prompt(EngineFixture.reportPrompt())
                .tool(EngineFixture.calculator())
                .modelProvider(model)
                .agent(EngineFixture.agent())
                .guard(Guards.Content.of("机密"))
                .events(bus)
                .build();

        RunResult result = engine.run(EngineFixture.AGENT_ID,
                Input.of("这是机密内容").withSlot("a", 1).withSlot("b", 1));

        assertEquals(SessionState.FAILED, result.state());
        assertTrue(result.error().contains("机密"), () -> "实际错误：" + result.error());
        assertEquals(1L, bus.count(Topics.GUARD_DENIED));
        assertEquals(0, model.requests().size(), "守卫拒绝后不应调用模型");
    }

    @Test
    @DisplayName("工具异常按重试策略重试并最终成功")
    void toolRetrySucceeds() {
        AtomicInteger attempts = new AtomicInteger();
        Tool flaky = Tools.of("flaky", "不稳定工具", ToolSchema.noArgs("flaky", "偶发失败"), input -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("临时故障");
            }
            return ToolResult.ok("最终成功");
        });
        WorkflowDefinition workflow = WorkflowBuilder.create("retry-wf", "1.0.0")
                .node(ToolNodeDefinition.of("call-flaky", "flaky", "flaky_result")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .slot("flaky_result", SlotType.STRING)
                .build();
        Engine engine = EngineBuilder.create()
                .workflow(workflow)
                .tool(ToolDefinition.of("flaky", ToolSchema.noArgs("flaky", "偶发失败"))
                        .withRetry(RetryPolicy.of(3, Duration.ofMillis(1))), flaky)
                .agent(AgentDefinition.builder("retry-agent").workflow("retry-wf").build())
                .build();

        assertEquals(3, engine.tools().definition("flaky").orElseThrow().retry().maxAttempts(),
                "工具定义上的重试策略应被注册");
        RunResult result = engine.run("retry-agent", Input.of("调用工具"));

        assertEquals(SessionState.COMPLETED, result.state(), () -> "实际错误：" + result.error());
        assertEquals(3, attempts.get());
        assertEquals("最终成功", result.slots().get("flaky_result"));
    }

    @Test
    @DisplayName("超出配额时会话失败")
    void quotaExceeded() {
        ScriptedModelProvider model = new ScriptedModelProvider("scripted").enqueueText("计划", "报告");
        Engine engine = EngineBuilder.create()
                .workflow(EngineFixture.pipeline())
                .prompt(EngineFixture.planPrompt())
                .prompt(EngineFixture.reportPrompt())
                .tool(EngineFixture.calculator())
                .modelProvider(model)
                .agent(EngineFixture.agent(null, QuotaPolicy.of(1000, 1, 10)))
                .build();

        RunResult result = engine.run(EngineFixture.AGENT_ID,
                Input.of("超配额").withSlot("a", 1).withSlot("b", 1));

        assertEquals(SessionState.FAILED, result.state());
        assertTrue(result.error().contains("配额"), () -> "实际错误：" + result.error());
    }

    @Test
    @DisplayName("取消会话后状态为 CANCELLED 并发布事件")
    void cancelSession() {
        InMemoryEventBus bus = new InMemoryEventBus();
        Engine engine = EngineBuilder.create()
                .workflow(EngineFixture.pipeline())
                .prompt(EngineFixture.planPrompt())
                .prompt(EngineFixture.reportPrompt())
                .tool(EngineFixture.calculator())
                .modelProvider(new ScriptedModelProvider("scripted"))
                .agent(EngineFixture.agent())
                .events(bus)
                .build();
        Session session = engine.startSession(engine.loadAgent(EngineFixture.AGENT_ID, "latest"),
                StartOptions.defaults());

        engine.cancel(session);

        assertEquals(SessionState.CANCELLED, session.state());
        assertEquals(1L, bus.count(Topics.SESSION_CANCELLED));
    }

    @Test
    @DisplayName("进程重启后可从持久化存储恢复挂起的会话")
    void resumeAfterRestart() {
        InMemorySessionStore sessionStore = new InMemorySessionStore();
        InMemorySlotStore slotStore = new InMemorySlotStore();
        ScriptedModelProvider firstModel = new ScriptedModelProvider("scripted")
                .enqueueText("草稿内容", "已发布：草稿内容");
        Engine firstEngine = approvalEngine(firstModel, sessionStore, slotStore);
        Session session = firstEngine.startSession(firstEngine.loadAgent("approval-agent", "latest"),
                StartOptions.defaults());

        RunResult suspended = firstEngine.run(session, Input.of("请起草一份通知"));
        assertEquals(SessionState.SUSPENDED, suspended.state());
        assertEquals("approve", sessionStore.load(session.id()).orElseThrow().cursor().nodeId());
        assertEquals("草稿内容", slotStore.load(session.id()).orElseThrow().values().get("draft").value());

        // 模拟进程重启：新引擎复用同一份存储，内存态为空
        ScriptedModelProvider secondModel = new ScriptedModelProvider("scripted").enqueueText("已发布：草稿内容");
        Engine restarted = approvalEngine(secondModel, sessionStore, slotStore);
        Session restored = restarted.contexts().load(session.id()).orElseThrow();
        RunResult resumed = restarted.resume(restored, Input.of("同意").withSlot("approval", "同意"));

        assertEquals(SessionState.COMPLETED, resumed.state());
        assertEquals("已发布：草稿内容", resumed.output());
        assertEquals(1, secondModel.requests().size(), "恢复后只应执行剩余的发布节点");
    }

    /**
     * 构建带人工节点的审批工作流引擎。
     *
     * @param model        脚本化模型
     * @param sessionStore 会话存储，可为 null
     * @param slotStore    槽位存储，可为 null
     * @return 引擎实例
     */
    private Engine approvalEngine(ScriptedModelProvider model, InMemorySessionStore sessionStore,
            InMemorySlotStore slotStore) {
        WorkflowDefinition workflow = WorkflowBuilder.create("approval-wf", "1.0.0")
                .node(LlmNodeDefinition.of("draft", "draft-prompt", "draft"))
                .node(new HumanNodeDefinition("approve", "请确认草稿：{{draft}}", "approval", "approval",
                        null, null, null))
                .node(LlmNodeDefinition.of("publish", "publish-prompt", "published")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .edge("draft", "approve")
                .edge("approve", "publish")
                .slot("draft", SlotType.STRING)
                .slot("approval", SlotType.STRING)
                .slot("published", SlotType.STRING)
                .build();
        events = new InMemoryEventBus();
        EngineBuilder builder = EngineBuilder.create()
                .workflow(workflow)
                .prompt(PromptDefinition.template("draft-prompt", "请起草通知：{{messages}}"))
                .prompt(PromptDefinition.template("publish-prompt", "请发布：{{slots.draft}}"))
                .modelProvider(model)
                .agent(AgentDefinition.builder("approval-agent").workflow("approval-wf")
                        .model("scripted", "m").build())
                .events(events);
        if (sessionStore != null) {
            builder.sessionStore(sessionStore);
        }
        if (slotStore != null) {
            builder.slotStore(slotStore);
        }
        return builder.build();
    }
}
