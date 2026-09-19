package com.agentframework.examples;

import com.agentframework.definition.agent.AgentDefinition;
import com.agentframework.definition.node.LlmNodeDefinition;
import com.agentframework.definition.node.NodeMeta;
import com.agentframework.definition.prompt.PromptDefinition;
import com.agentframework.definition.workflow.SlotType;
import com.agentframework.definition.workflow.WorkflowBuilder;
import com.agentframework.definition.workflow.WorkflowDefinition;
import com.agentframework.engine.core.RunResult;
import com.agentframework.infra.modelgateway.EchoModelProvider;
import com.agentframework.sdk.AgentFramework;

/**
 * 最小示例：只有一个 LLM 节点的工作流，用回声模型离线跑通整条链路。
 *
 * <pre>
 * mvn -o -q compile
 * java -cp target/classes com.agentframework.examples.QuickstartExample
 * </pre>
 */
public final class QuickstartExample {

    private QuickstartExample() {
    }

    /**
     * 程序入口。
     *
     * @param args 未使用
     */
    public static void main(String[] args) {
        WorkflowDefinition workflow = WorkflowBuilder.create("hello-workflow", "1.0.0")
                .node(LlmNodeDefinition.of("greet", "greet-prompt", "greeting")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .slot("greeting", SlotType.STRING)
                .build();

        AgentDefinition agent = AgentDefinition.builder("greeter", "1.0.0")
                .workflow("hello-workflow")
                .model("echo", "quick")
                .build();

        try (AgentFramework framework = new AgentFramework(AgentFramework.create()
                .workflow(workflow)
                .prompt(PromptDefinition.template("greet-prompt", "请用一句话问候用户：{{messages}}"))
                .agent(agent)
                .modelProvider(new EchoModelProvider("echo",
                        request -> "你好，我是 Agent 框架：" + request.lastUserMessage()))
                .build())) {

            RunResult result = framework.run("greeter", "请介绍一下你自己");
            System.out.println("状态：" + result.state());
            System.out.println("输出：" + result.output());
            System.out.println("槽位：" + result.slots());
            System.out.println("执行路径：" + result.visitedNodes());
            System.out.println("执行轨迹：");
            result.executionTrace().forEach(step -> System.out.println("  " + step.describe()));
        }
    }
}
