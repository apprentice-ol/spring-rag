package com.jjx.customer.platform.business;
import com.jjx.customer.platform.config.llm.JsonProtocolModelPort;

import com.jjx.customer.platform.agent.framework.agent.Agent;
import com.jjx.customer.platform.agent.framework.prompt.PromptSnapshotSource;
import com.jjx.customer.platform.agent.framework.tool.AgentTool;
import com.jjx.customer.platform.agent.framework.workflow.Workflow;
import com.jjx.customer.platform.agent.framework.workflow.WorkflowCatalog;
import com.jjx.customer.platform.agent.framework.agent.ExecutionPlanner;
import com.jjx.customer.platform.agent.framework.agent.WorkflowEngine;
import com.jjx.customer.platform.agent.framework.agent.AgentWorkflowBindingResolver;
import com.jjx.customer.platform.agent.framework.workflow.DefaultWorkflowDriver;
import com.jjx.customer.platform.agent.framework.model.ModelPort;
import com.jjx.customer.platform.agent.framework.node.AgentCallNodeExecutor;
import com.jjx.customer.platform.agent.framework.node.DeterministicNodeExecutor;
import com.jjx.customer.platform.agent.framework.node.LoopNodeExecutor;
import com.jjx.customer.platform.agent.framework.node.NodeExecutorRegistry;
import com.jjx.customer.platform.agent.framework.agent.AgentRegistry;
import com.jjx.customer.platform.agent.framework.workflow.InMemoryWorkflowCatalog;
import com.jjx.customer.platform.agent.framework.route.RouteStrategy;
import com.jjx.customer.platform.agent.framework.route.RouteTable;
import com.jjx.customer.platform.agent.framework.session.SessionRecordingListener;
import com.jjx.customer.platform.agent.framework.session.SessionStore;
import com.jjx.customer.platform.agent.framework.spi.ExecutionListener;
import com.jjx.customer.platform.agent.framework.tool.ToolRegistry;
import com.jjx.customer.platform.mcp.McpExtensionToolSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.SmartLifecycle;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.List;

/**
 * Agent 框架装配（业务侧直配，无 starter）：引擎与其 SPI 实现全部在本类显式组装。
 *
 * <p>注入的都是框架契约类型（{@code com.jjx.customer.agent.*}）——业务实现的 Agent /
 * Workflow / ExtensionTool / RouteStrategy 由 Spring 自动收集后交给框架。
 * 新增一个能力域 = 新增业务 Agent + Workflow + 工具 + 一条 RouteStrategy，本类不改。</p>
 */
@Configuration
public class FrameworkAgentConfiguration {

    /**
     * 模型端口：按 {@code rag.chat.agent.tool-call-mode} 装配。
     * <ul>
     *   <li>native（默认）：原生 function calling——工具 schema → 模型工具定义 →
     *       结构化回传，工具执行权留在框架（internalToolExecutionEnabled=false 手动驱动）；</li>
     *   <li>json：JSON 文本协议降级逃生门（模型端 function calling 遵循率差时切换）。</li>
     * </ul>
     */
    @Bean
    public ModelPort frameworkModelPort(@Qualifier("ingestionChatClient") ChatClient chatClient,
                                        org.springframework.ai.chat.model.ChatModel chatModel,
                                        com.jjx.customer.platform.config.properties.AgentProperties agentProperties) {
        if ("json".equalsIgnoreCase(agentProperties.getToolCallMode())) {
            return new JsonProtocolModelPort((system, user) -> chatClient.prompt()
                    .system(system)
                    .user(user)
                    .call()
                    .content());
        }
        return new com.jjx.customer.platform.config.llm.NativeToolModelPort(chatModel);
    }

    @Bean
    public ToolRegistry frameworkToolRegistry(List<AgentTool> tools, McpExtensionToolSource mcpSource) {
        List<AgentTool> all = new java.util.ArrayList<>(tools);
        all.addAll(mcpSource.tools());
        return new ToolRegistry(all);
    }

    @Bean
    public NodeExecutorRegistry frameworkNodeExecutors(ToolRegistry toolRegistry, ModelPort modelPort) {
        return new NodeExecutorRegistry(List.of(
                new DeterministicNodeExecutor(toolRegistry),
                new LoopNodeExecutor(modelPort, toolRegistry),
                new AgentCallNodeExecutor()));
    }

    @Bean
    public WorkflowCatalog frameworkWorkflowCatalog(List<Workflow> workflows) {
        return new InMemoryWorkflowCatalog(workflows);
    }

    @Bean
    public RouteTable frameworkRouteTable(List<RouteStrategy> strategies) {
        return new RouteTable(strategies);
    }

    @Bean
    public AgentRegistry frameworkAgentRegistry(List<Agent> agents) {
        return new AgentRegistry(agents);
    }

    /** 绑定解析（桥）：默认实现 = 用 Agent 自身声明的 Workflow；业务可另注册实现覆盖换绑。 */
    @Bean
    public AgentWorkflowBindingResolver frameworkAgentWorkflowBindingResolver() {
        return AgentWorkflowBindingResolver.DEFAULT;
    }

    @Bean
    public ExecutionPlanner frameworkExecutionPlanner(RouteTable routeTable,
                                                     WorkflowCatalog workflowCatalog,
                                                     AgentWorkflowBindingResolver bindingResolver,
                                                     PromptSnapshotSource promptSnapshotSource,
                                                     CacheAwareCapabilityConfig capabilityConfig) {
        // 能力开关：缓存层关闭即收窄 ANSWER_CACHE/SEMANTIC_CACHE（三方模型驱动管线行为）
        return new ExecutionPlanner(routeTable, workflowCatalog,
                bindingResolver, capabilityConfig, promptSnapshotSource);
    }

    @Bean
    public WorkflowEngine frameworkWorkflowEngine(ExecutionPlanner planner,
                                                 NodeExecutorRegistry nodeExecutors,
                                                 AgentRegistry agentRegistry,
                                                 ModelPort modelPort,
                                                 SessionStore sessionStore) {
        // 骨架装配：监听器（会话落库：CLARIFY → AWAITING_USER，终态 → DONE）+ 拦截器（批次 E 接入）
        // 驱动注入 ModelPort（槽位 LLM 抽槽与 replan 裁决经端口调用）
        List<ExecutionListener> listeners = List.of(new SessionRecordingListener(sessionStore));
        return new WorkflowEngine(planner,
                new DefaultWorkflowDriver(nodeExecutors, modelPort, List.of()),
                agentRegistry, List.of(), 3, listeners);
    }

    /**
     * 框架生命周期接入 Spring 容器生命周期（Adapter，Spring {@link SmartLifecycle} 范式）：
     * 容器 refresh 完成后调 {@code engine.start()}（监听器 onEngineStart：冻结注册表/预热端口），
     * 容器关闭时调 {@code engine.stop()}（监听器 onEngineStop：冲刷/释放）。
     */
    @Bean
    public SmartLifecycle frameworkWorkflowEngineLifecycle(WorkflowEngine engine) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                engine.start();
                running = true;
            }

            @Override
            public void stop() {
                engine.stop();
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running;
            }
        };
    }
}
