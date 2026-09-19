package com.agentframework.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentframework.definition.node.LlmNodeDefinition;
import com.agentframework.definition.node.NodeMeta;
import com.agentframework.definition.workflow.WorkflowDefinition;
import com.agentframework.engine.core.RunResult;
import com.agentframework.infra.modelgateway.EchoModelProvider;
import com.agentframework.runtime.session.Input;
import com.agentframework.runtime.session.SessionState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 应用层 SDK 测试：门面 + 构建助手的组合用法。 */
class AgentFrameworkSdkTest {

    @Test
    @DisplayName("SDK 门面可一步跑通一条最简执行链")
    void fluentSdkRunsAgent() {
        WorkflowDefinition workflow = Workflows.builder("sdk-wf")
                .node(LlmNodeDefinition.of("greet", "greet-prompt", "greeting")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .slot("greeting", com.agentframework.definition.workflow.SlotType.STRING)
                .build();

        try (AgentFramework framework = new AgentFramework(AgentFramework.create()
                .workflow(workflow)
                .prompt(Prompts.template("greet-prompt", "请问候用户：{{messages}}"))
                .tool(Tools.constant("noop", "未使用"))
                .modelProvider(new EchoModelProvider("echo", request -> "你好：" + request.lastUserMessage()))
                .agent(Agents.define("sdk-agent", "sdk-wf").model("echo", "quick").build())
                .build())) {

            RunResult result = framework.run("sdk-agent", "早上好");

            assertEquals(SessionState.COMPLETED, result.state());
            assertTrue(result.output().contains("你好"));
            assertTrue(result.messages().size() >= 2);
            assertEquals(1L, framework.engine().metrics().snapshot().size() > 0 ? 1L : 0L);
        }
    }

    @Test
    @DisplayName("SDK 支持在已有会话上继续运行")
    void sdkKeepsSession() {
        WorkflowDefinition workflow = Workflows.builder("sdk-wf-2")
                .node(LlmNodeDefinition.of("echo", "echo-prompt", "echo_out")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .build();

        try (AgentFramework framework = new AgentFramework(AgentFramework.create()
                .workflow(workflow)
                .prompt(Prompts.template("echo-prompt", "回声"))
                .modelProvider(new EchoModelProvider("echo", request -> "回声：" + request.lastUserMessage()))
                .agent(Agents.define("sdk-agent-2", "sdk-wf-2").model("echo", "quick").build())
                .build())) {

            var session = framework.startSession("sdk-agent-2",
                    com.agentframework.runtime.session.StartOptions.defaults());
            RunResult first = framework.run(session, "第一句");
            RunResult second = framework.run(session, "第二句");

            assertTrue(first.output().contains("第一句"));
            assertTrue(second.output().contains("第二句"));
            assertEquals(session.id(), second.sessionId());
        }
    }
}
