package com.jjx.customer.platform.business.engine;

import com.agentframework.crosscutting.interceptor.TaskPropagation;
import com.agentframework.definition.agent.AgentDefinition;
import com.agentframework.definition.policy.QuotaPolicy;
import com.agentframework.definition.policy.ToolPolicy;
import com.agentframework.definition.workflow.WorkflowDefinition;
import com.agentframework.engine.core.Engine;
import com.agentframework.engine.core.EngineBuilder;
import com.agentframework.engine.toolexecutor.DefaultToolExecutor;
import com.agentframework.engine.toolexecutor.DefaultToolRegistry;
import com.agentframework.infra.modelgateway.DefaultModelGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.customer.platform.business.engine.AgentCatalog;
import com.jjx.customer.platform.business.engine.adapter.GatewayModelAdapter;
import com.jjx.customer.platform.business.engine.persistence.PgEngineSessionStore;
import com.jjx.customer.platform.business.engine.persistence.PgEngineSlotStore;
import com.jjx.customer.platform.business.knowledge.node.KbFinishExecutor;
import com.jjx.customer.platform.business.knowledge.KbPrompts;
import com.jjx.customer.platform.business.knowledge.workflow.KnowledgeQaGraphFactory;
import com.jjx.customer.platform.business.knowledge.workflow.KnowledgeReactGraphFactory;
import com.jjx.customer.platform.business.knowledge.node.KbClassifyExecutor;
import com.jjx.customer.platform.business.knowledge.node.KbCritiqueExecutor;
import com.jjx.customer.platform.business.knowledge.node.KbNormalizeExecutor;
import com.jjx.customer.platform.business.knowledge.node.KbRewriteExecutor;
import com.jjx.customer.platform.business.knowledge.node.KbRouteExecutor;
import com.jjx.customer.platform.business.knowledge.node.KbShortCircuitExecutor;
import com.jjx.customer.platform.business.knowledge.intent.IntentClassifier;
import com.jjx.customer.platform.business.knowledge.normalize.QueryRewriter;
import com.jjx.customer.platform.routing.RouteRegistry;
import com.jjx.customer.platform.business.ops.slot.OpsSlotCatalog;
import com.jjx.customer.platform.business.ops.workflow.OpsDiagnoseWorkflowFactory;
import com.jjx.customer.platform.business.ops.OpsPrompts;
import com.jjx.customer.platform.business.ops.OpsProperties;
import com.jjx.customer.platform.business.workflow.common.ActExecutor;
import com.jjx.customer.platform.observe.openobserve.OpenObserveLogQueryClient;
import com.jjx.customer.platform.business.ops.workflow.stages.SharedDeps;
import com.jjx.customer.platform.business.ops.workflow.stages.StageModule;
import com.jjx.customer.platform.business.ops.tool.CurrentTimeTool;
import com.jjx.customer.platform.business.ops.tool.QueryLogsTool;
import com.jjx.customer.platform.business.ops.tool.ValidateRequestTool;
import com.jjx.ai.llmobservability.observation.propagation.ContextPropagator;
import com.jjx.customer.platform.config.llm.SpringAiModelProvider;
import com.jjx.customer.platform.config.prompt.PromptStore;
import com.jjx.customer.platform.config.properties.AgentProperties;
import com.jjx.customer.platform.config.properties.ChatProperties;
import com.jjx.customer.platform.knowledge.retrieval.RetrievalEngine;
import com.jjx.customer.platform.knowledge.tools.RetrievalTool;
import com.jjx.customer.platform.prompt.snapshot.PromptStorePromptProvider;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 新内核引擎装配（业务侧直配，无 starter）：{@link Engine} 与全部桥接组件在本类显式组装。
 *
 * <p>单引擎三 Agent（ops_diagnose / knowledge / react_loop）：会话状态与恢复管理都是
 * 引擎级，双引擎会让追问恢复断链。装配即键控——ops 图的执行器 / Prompt / 守卫由
 * {@code StageModule} 自带并经 {@link OpsDiagnoseWorkflowFactory#reconcile} 对账
 * （图是唯一事实源）；knowledge 双图在引擎上直接注册。</p>
 *
 * <p>Prompt 资产管道见 {@link PromptAssetConfiguration}；引擎会话持久化走
 * {@code ops_engine_session/slots}（挂起恢复），澄清会话仍由
 * {@code AgentSessionServiceImpl}（sa_agent_session）承担。</p>
 */
@Configuration
@EnableConfigurationProperties(OpsProperties.class)
public class AgentEngineConfiguration {

    /**
     * 共享模型网关：唯一 provider = spring-ai（宿主 ChatModel，DeepSeek 端点）。
     * LLM 节点、工具循环 think、抽槽/裁决边角调用同一网关同一横切（指标/超时/重试）。
     */
    @Bean
    public DefaultModelGateway agentModelGateway(ChatModel chatModel) {
        DefaultModelGateway gateway = new DefaultModelGateway(SpringAiModelProvider.PROVIDER_ID, null);
        gateway.register(new SpringAiModelProvider(chatModel));
        return gateway;
    }

    /**
     * 把调用方线程的遥测上下文搬进引擎的执行线程。
     *
     * <p>引擎的超时拦截器会把节点执行丢到新开的虚拟线程上跑，而新线程不继承线程本地变量——
     * 链路追踪上下文（以及会话环境 HOLDER）会原地丢失，节点里产生的 span 只能各自成为根 trace，
     * 一次问答在 OpenObserve 里断成互不相干的好几截。装配期注入这个包装器即可修复，
     * 内核侧对应的扩展点是 {@code TaskPropagation}。</p>
     *
     * <p>放在 {@code @PostConstruct} 而不是某个 bean 方法里：这是进程级的静态装配，
     * 必须在任何一次 {@code engine.run} 之前完成，不能依赖"某个 bean 恰好先被创建"。</p>
     */
    @jakarta.annotation.PostConstruct
    void installTelemetryTaskPropagation() {
        TaskPropagation.install(ContextPropagator::wrap);
    }

    /**
     * 引擎本体（destroyMethod=close 释放内部资源）。
     *
     * <p>Prompt 来源注入容器单例 {@link PromptStorePromptProvider}（{@link PromptAssetConfiguration}
     * 装配）：组合模板与 runtime 注册均落在该单例上。先前是本类自调用再造一个私有实例
     * （CGLIB 代理不自调用）——该 provider 全仓仅引擎消费，单例化后解析结果逐字节不变。</p>
     */
    @Bean(destroyMethod = "close")
    public Engine agentEngine(ChatModel chatModel,
                              PromptStorePromptProvider promptProvider,
                              RetrievalEngine retrievalEngine,
                              DataSource dataSource,
                              ObjectMapper objectMapper,
                              AgentProperties agentProperties,
                              ChatProperties chatProperties,
                              OpsProperties opsProperties,
                              com.jjx.customer.platform.mcp.McpExtensionToolSource mcpToolSource,
                              IntentClassifier intentClassifier,
                              RouteRegistry routeRegistry,
                              QueryRewriter queryRewriter,
                              @org.springframework.beans.factory.annotation.Value(
                                      "${rag.rerank.min-relevance-score:0.0}") double minRelevanceScore) {

        // ---- 工具面（共享注册表：RAG 检索 + ops 诊断 + 时间）----
        DefaultToolRegistry sharedRegistry = new DefaultToolRegistry();
        RetrievalTool retrievalTool = new RetrievalTool(retrievalEngine,
                chatProperties.getTopK(), chatProperties.getSimilarityThreshold(),
                chatProperties.getRecallBudget(), chatProperties.getCandidateLimit(),
                chatProperties.getContextTopK(), minRelevanceScore);
        ValidateRequestTool validateTool = new ValidateRequestTool(objectMapper);
        CurrentTimeTool currentTimeTool = new CurrentTimeTool();
        OpenObserveLogQueryClient ooClient = new OpenObserveLogQueryClient(opsProperties.openobserveOrDefault(), objectMapper);
        QueryLogsTool queryLogsTool = new QueryLogsTool(ooClient, opsProperties.logsCacheOrDefault(),
                opsProperties.openobserveOrDefault());
        sharedRegistry.register(retrievalTool);
        sharedRegistry.register(validateTool);
        sharedRegistry.register(currentTimeTool);
        sharedRegistry.register(queryLogsTool);
        // MCP 外部工具（默认关闭；启用时与本地工具同权进共享注册表）
        mcpToolSource.tools().forEach(sharedRegistry::register);

        DefaultToolExecutor toolExecutor = new DefaultToolExecutor(sharedRegistry, null, null, null);
        DefaultModelGateway gateway = agentModelGateway(chatModel);
        GatewayModelAdapter sideModel = new GatewayModelAdapter(gateway);
        int maxLlmCalls = agentProperties.getWorkflow().getMaxLlmCalls();

        // Prompt 协议块描述（think 模板组合用）
        Map<String, String> schemaText = Map.of(
                RetrievalTool.TOOL_ID, OpsPrompts.describeTool(retrievalTool.schema()),
                ValidateRequestTool.TOOL_ID, OpsPrompts.describeTool(validateTool.schema()),
                CurrentTimeTool.TOOL_ID, OpsPrompts.describeTool(currentTimeTool.schema()),
                QueryLogsTool.TOOL_ID, OpsPrompts.describeTool(queryLogsTool.schema()));
        java.util.function.Function<String, String> promptBody = key -> {
            try {
                return promptProvider.get(key, "latest").template();
            } catch (Exception e) {
                return null;
            }
        };
        SharedDeps deps = new SharedDeps(sharedRegistry, toolExecutor, objectMapper, sideModel,
                Clock.systemDefaultZone(), maxLlmCalls, schemaText, validateTool,
                promptBody, promptProvider::registerRuntimeTemplate);

        // ---- 引擎装配 ----
        EngineBuilder builder = EngineBuilder.create()
                .toolRegistry(sharedRegistry)
                .modelGateway(gateway)
                .promptProvider(promptProvider)
                .sessionStore(new PgEngineSessionStore(new JdbcTemplate(dataSource), objectMapper))
                .slotStore(new PgEngineSlotStore(new JdbcTemplate(dataSource), objectMapper));

        // ops 线：图 ↔ 模块对账（图是唯一事实源）→ 逐模块 wireRuntime → 注册图与 Agent
        WorkflowDefinition opsWorkflow = OpsDiagnoseWorkflowFactory.create();
        Map<String, StageModule> modules = OpsDiagnoseWorkflowFactory.modules();
        OpsDiagnoseWorkflowFactory.reconcile(opsWorkflow, modules.values());
        modules.values().forEach(module -> module.wireRuntime(builder, deps));
        builder.workflow(opsWorkflow)
                .agent(AgentDefinition.builder(AgentCatalog.OPS.id())
                        .workflow(OpsDiagnoseWorkflowFactory.WORKFLOW_ID)
                        .model(SpringAiModelProvider.PROVIDER_ID, "chat")
                        .toolPolicy(ToolPolicy.only(QueryLogsTool.TOOL_ID, RetrievalTool.TOOL_ID,
                                ValidateRequestTool.TOOL_ID, CurrentTimeTool.TOOL_ID))
                        .quota(QuotaPolicy.of(2_000_000, agentProperties.getWorkflow().getTimeoutSeconds(), 60))
                        .build());

        // knowledge 线：双图（查询理解链 + 检索反思环 / 查询理解链 + think→act→decide 工具循环）+ 两 Agent
        // 查询理解链的执行器由两图共用（同一个 bean 实例注册一次，两图都能解析到）
        builder.workflow(KnowledgeQaGraphFactory.create(agentProperties.getMaxRewriteRounds(),
                        agentProperties.getShortCircuitDomains()))
                .workflow(KnowledgeReactGraphFactory.create(agentProperties.getReactMaxSteps(),
                        agentProperties.getShortCircuitDomains()))
                .nodeExecutor(KbFinishExecutor.EXECUTOR_REF, new KbFinishExecutor())
                .nodeExecutor(KnowledgeQaGraphFactory.NORMALIZE_EXECUTOR, new KbNormalizeExecutor())
                .nodeExecutor(KnowledgeQaGraphFactory.CLASSIFY_EXECUTOR,
                        new KbClassifyExecutor(intentClassifier))
                .nodeExecutor(KnowledgeQaGraphFactory.ROUTE_EXECUTOR, new KbRouteExecutor(routeRegistry))
                .nodeExecutor(KnowledgeQaGraphFactory.SHORTCIRCUIT_EXECUTOR, new KbShortCircuitExecutor())
                .nodeExecutor(KnowledgeQaGraphFactory.REWRITE_EXECUTOR, new KbRewriteExecutor(queryRewriter))
                .nodeExecutor(KnowledgeQaGraphFactory.CRITIQUE_EXECUTOR,
                        // 判据从配置来：条数下限是**语料相关**的（单跳 1 / HotpotQA 2.4 /
                        // 2Wiki 4.9），硬编码会让换语料变成改代码。见 AgentProperties.Critique
                        new KbCritiqueExecutor(agentProperties.getCritique().getRequiredHits(),
                                agentProperties.getCritique().getMinTopScore()))
                .nodeExecutor(KnowledgeReactGraphFactory.ACT_EXECUTOR,
                        new ActExecutor(KnowledgeReactGraphFactory.PREFIX, "工具循环检索",
                                Set.of(RetrievalTool.TOOL_ID), sharedRegistry, toolExecutor,
                                objectMapper, maxLlmCalls,
                                OpsSlotCatalog.askableNames(), null))
                .loopGuard(KnowledgeReactGraphFactory.LOOP_GUARD, agentProperties.getReactMaxSteps())
                .loopGuard(KnowledgeQaGraphFactory.ITERATION_GUARD, agentProperties.getMaxRewriteRounds())
                .agent(AgentDefinition.builder(AgentCatalog.KNOWLEDGE.id())
                        .workflow(KnowledgeQaGraphFactory.WORKFLOW_ID)
                        .model(SpringAiModelProvider.PROVIDER_ID, "chat")
                        .toolPolicy(ToolPolicy.only(RetrievalTool.TOOL_ID))
                        .build())
                .agent(AgentDefinition.builder(AgentCatalog.REACT.id())
                        .workflow(KnowledgeReactGraphFactory.WORKFLOW_ID)
                        .model(SpringAiModelProvider.PROVIDER_ID, "chat")
                        .toolPolicy(ToolPolicy.only(RetrievalTool.TOOL_ID))
                        .build());

        // react think 组合模板（正文资产优先，协议块附后）
        promptProvider.registerRuntimeTemplate(KbPrompts.REACT_THINK_ASSET,
                KbPrompts.composeReactThink(promptBody.apply(KbPrompts.REACT_THINK_ASSET),
                        List.of(schemaText.get(RetrievalTool.TOOL_ID))));

        return builder.build();
    }
}
