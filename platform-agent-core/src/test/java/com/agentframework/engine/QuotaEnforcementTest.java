package com.agentframework.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentframework.definition.agent.AgentDefinition;
import com.agentframework.definition.node.LlmNodeDefinition;
import com.agentframework.definition.node.NodeMeta;
import com.agentframework.definition.node.ToolNodeDefinition;
import com.agentframework.definition.policy.QuotaPolicy;
import com.agentframework.definition.prompt.PromptDefinition;
import com.agentframework.definition.workflow.SlotType;
import com.agentframework.definition.workflow.WorkflowBuilder;
import com.agentframework.definition.workflow.WorkflowDefinition;
import com.agentframework.engine.core.Engine;
import com.agentframework.engine.core.EngineBuilder;
import com.agentframework.engine.core.RunResult;
import com.agentframework.engine.toolexecutor.ToolResult;
import com.agentframework.infra.modelgateway.EchoModelProvider;
import com.agentframework.runtime.session.Input;
import com.agentframework.runtime.session.SessionState;
import com.agentframework.sdk.Tools;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 配额补齐测试：token、工具调用与墙钟时间上限真正生效。 */
class QuotaEnforcementTest {

    @Test
    @DisplayName("token 上限按节点用量累计并中断运行")
    void tokenQuotaIsEnforced() {
        Engine engine = llmEngine(QuotaPolicy.of(1, 50, 50));

        RunResult result = engine.run("token-agent", Input.of("你好"));

        assertEquals(SessionState.FAILED, result.state());
        assertTrue(result.error().contains("tokens"), () -> "实际错误：" + result.error());
    }

    @Test
    @DisplayName("工具调用上限在第二次调用时中断运行")
    void toolCallQuotaIsEnforced() {
        WorkflowDefinition workflow = WorkflowBuilder.create("tool-quota-wf", "1.0.0")
                .node(ToolNodeDefinition.of("call-1", "adder", "out_1"))
                .node(ToolNodeDefinition.of("call-2", "adder", "out_2")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .edge("call-1", "call-2")
                .build();
        Engine engine = EngineBuilder.create()
                .workflow(workflow)
                .tool(Tools.of("adder", "加法", input -> ToolResult.ok("ok")))
                .agent(AgentDefinition.builder("tool-quota-agent").workflow("tool-quota-wf")
                        .quota(QuotaPolicy.of(-1, 50, 1))
                        .build())
                .build();

        RunResult result = engine.run("tool-quota-agent", Input.of("算一下"));

        assertEquals(SessionState.FAILED, result.state());
        assertTrue(result.error().contains("toolCalls"), () -> "实际错误：" + result.error());
    }

    @Test
    @DisplayName("墙钟上限在超时后中断运行")
    void wallTimeQuotaIsEnforced() {
        Engine engine = llmEngine(new QuotaPolicy(-1, 50, 50, Duration.ofNanos(1)));

        RunResult result = engine.run("token-agent", Input.of("你好"));

        assertEquals(SessionState.FAILED, result.state());
        assertTrue(result.error().contains("wallTime"), () -> "实际错误：" + result.error());
    }

    @Test
    @DisplayName("不限制配额时运行正常完成")
    void unlimitedQuotaRunsToCompletion() {
        Engine engine = llmEngine(QuotaPolicy.unlimited());

        RunResult result = engine.run("token-agent", Input.of("你好"));

        assertEquals(SessionState.COMPLETED, result.state(), () -> "实际错误：" + result.error());
    }

    /**
     * @param quota Agent 配额策略
     * @return 单 LLM 节点的引擎
     */
    private Engine llmEngine(QuotaPolicy quota) {
        WorkflowDefinition workflow = WorkflowBuilder.create("token-wf", "1.0.0")
                .node(LlmNodeDefinition.of("hello", "hello-prompt", "greeting")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .slot("greeting", SlotType.STRING)
                .build();
        return EngineBuilder.create()
                .workflow(workflow)
                .prompt(PromptDefinition.template("hello-prompt", "问候：{{messages}}"))
                .modelProvider(new EchoModelProvider("echo", request -> "你好"))
                .defaultModelProvider("echo")
                .agent(AgentDefinition.builder("token-agent").workflow("token-wf")
                        .quota(quota)
                        .build())
                .build();
    }
}
