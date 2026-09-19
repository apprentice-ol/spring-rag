package com.agentframework.engine.core;

import com.agentframework.crosscutting.cache.CacheStore;
import com.agentframework.crosscutting.metrics.Metrics;
import com.agentframework.crosscutting.trace.Tracer;
import com.agentframework.engine.agentmanager.AgentManager;
import com.agentframework.engine.agentmanager.DefinitionSource;
import com.agentframework.engine.contextmanager.ContextManager;
import com.agentframework.engine.middleware.MiddlewarePipeline;
import com.agentframework.engine.persistence.PersistenceManager;
import com.agentframework.engine.pluginruntime.PluginRuntime;
import com.agentframework.engine.policy.PolicyCatalog;
import com.agentframework.engine.policy.PolicyResolver;
import com.agentframework.engine.policy.RegionMetricsCollector;
import com.agentframework.engine.promptmanager.PromptManager;
import com.agentframework.engine.scheduling.RecoveryManager;
import com.agentframework.engine.scheduling.Scheduler;
import com.agentframework.engine.toolexecutor.ToolExecutor;
import com.agentframework.engine.toolexecutor.ToolRegistry;
import com.agentframework.engine.workflowruntime.NodeExecutorRegistry;
import com.agentframework.engine.workflowruntime.WorkflowRuntime;
import com.agentframework.extension.permission.QuotaEnforcer;
import com.agentframework.extension.registry.ExtensionRegistry;
import com.agentframework.infra.modelgateway.ModelGateway;
import com.agentframework.runtime.event.EventBus;

/**
 * 引擎装配结果：所有内核协作者的只读集合。
 *
 * <p>用不可变记录承载依赖，让 {@link DefaultEngine} 与节点执行器拿到同一套组件，
 * 避免出现“两套注册表”这类难查的装配错误。</p>
 *
 * @param config          引擎配置
 * @param definitions     定义来源
 * @param agents          Agent 管理器
 * @param contexts        上下文管理器
 * @param prompts         Prompt 管理器
 * @param modelGateway    模型网关
 * @param tools           工具注册表
 * @param toolExecutor    工具执行器
 * @param middleware      中间件管道
 * @param nodeExecutors   节点执行器注册表
 * @param workflowRuntime 工作流运行时
 * @param tracer          追踪器
 * @param metrics         指标采集器
 * @param events          事件总线
 * @param cacheStore      缓存存储
 * @param extensions      扩展注册表
 * @param quotaEnforcer   配额执行器
 * @param plugins         插件运行时
 * @param persistence     持久化管理器
 * @param recovery        恢复管理器
 * @param scheduler       调度器
 * @param policyResolver  策略解析器
 * @param policyCatalog   策略组件目录
 * @param regionMetrics   Region 指标采集器
 */
public record EngineServices(
        EngineConfig config,
        DefinitionSource definitions,
        AgentManager agents,
        ContextManager contexts,
        PromptManager prompts,
        ModelGateway modelGateway,
        ToolRegistry tools,
        ToolExecutor toolExecutor,
        MiddlewarePipeline middleware,
        NodeExecutorRegistry nodeExecutors,
        WorkflowRuntime workflowRuntime,
        Tracer tracer,
        Metrics metrics,
        EventBus events,
        CacheStore cacheStore,
        ExtensionRegistry extensions,
        QuotaEnforcer quotaEnforcer,
        PluginRuntime plugins,
        PersistenceManager persistence,
        RecoveryManager recovery,
        Scheduler scheduler,
        PolicyResolver policyResolver,
        PolicyCatalog policyCatalog,
        RegionMetricsCollector regionMetrics) {
}
