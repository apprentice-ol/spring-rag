package com.agentframework.engine.core;

import com.agentframework.crosscutting.cache.CacheStore;
import com.agentframework.crosscutting.cache.ContentHashCacheKeyBuilder;
import com.agentframework.crosscutting.cache.InMemoryCacheStore;
import com.agentframework.crosscutting.filter.Filter;
import com.agentframework.crosscutting.guard.Guard;
import com.agentframework.crosscutting.guard.Guards;
import com.agentframework.crosscutting.interceptor.Interceptor;
import com.agentframework.crosscutting.interceptor.Interceptors;
import com.agentframework.crosscutting.metrics.InMemoryMetrics;
import com.agentframework.crosscutting.metrics.Metrics;
import com.agentframework.crosscutting.trace.SimpleTracer;
import com.agentframework.crosscutting.trace.Tracer;
import com.agentframework.definition.agent.AgentDefinition;
import com.agentframework.definition.prompt.PromptDefinition;
import com.agentframework.definition.tool.ToolDefinition;
import com.agentframework.definition.workflow.WorkflowDefinition;
import com.agentframework.engine.agentmanager.AgentManager;
import com.agentframework.engine.agentmanager.AgentValidator;
import com.agentframework.engine.agentmanager.DefaultAgentManager;
import com.agentframework.engine.agentmanager.DefinitionSource;
import com.agentframework.engine.agentmanager.InMemoryDefinitionSource;
import com.agentframework.engine.contextmanager.DefaultContextManager;
import com.agentframework.engine.middleware.DefaultMiddlewarePipeline;
import com.agentframework.engine.middleware.MiddlewarePipeline;
import com.agentframework.engine.persistence.PersistenceManager;
import com.agentframework.engine.policy.Activation;
import com.agentframework.engine.policy.PolicyCatalog;
import com.agentframework.engine.policy.PolicyComponent;
import com.agentframework.engine.policy.PolicyFactory;
import com.agentframework.engine.policy.PolicyKind;
import com.agentframework.engine.policy.PolicyResolver;
import com.agentframework.engine.policy.RegionMetricsCollector;
import com.agentframework.engine.pluginruntime.PluginRuntime;
import com.agentframework.engine.promptmanager.DefaultPromptManager;
import com.agentframework.engine.promptmanager.InMemoryPromptProvider;
import com.agentframework.engine.promptmanager.PromptManager;
import com.agentframework.engine.promptmanager.PromptProvider;
import com.agentframework.engine.promptmanager.PromptRenderer;
import com.agentframework.engine.scheduling.DefaultScheduler;
import com.agentframework.engine.scheduling.RecoveryManager;
import com.agentframework.engine.scheduling.Scheduler;
import com.agentframework.engine.toolexecutor.DefaultToolExecutor;
import com.agentframework.engine.toolexecutor.DefaultToolRegistry;
import com.agentframework.engine.toolexecutor.Tool;
import com.agentframework.engine.toolexecutor.ToolExecutor;
import com.agentframework.engine.toolexecutor.ToolRegistry;
import com.agentframework.engine.workflowruntime.DefaultWorkflowRuntime;
import com.agentframework.engine.workflowruntime.NodeExecutor;
import com.agentframework.engine.workflowruntime.NodeExecutorRegistry;
import com.agentframework.engine.workflowruntime.WorkflowRuntime;
import com.agentframework.engine.workflowruntime.executors.ConditionNodeExecutor;
import com.agentframework.engine.workflowruntime.executors.CustomNodeExecutor;
import com.agentframework.engine.workflowruntime.executors.HumanNodeExecutor;
import com.agentframework.engine.workflowruntime.executors.LlmNodeExecutor;
import com.agentframework.engine.workflowruntime.executors.ParallelNodeExecutor;
import com.agentframework.engine.workflowruntime.executors.SubWorkflowNodeExecutor;
import com.agentframework.engine.workflowruntime.executors.ToolNodeExecutor;
import com.agentframework.extension.permission.PermissionChecker;
import com.agentframework.extension.permission.PermissionSet;
import com.agentframework.extension.permission.QuotaEnforcer;
import com.agentframework.extension.registry.DefaultExtensionRegistry;
import com.agentframework.extension.registry.ExtensionRegistry;
import com.agentframework.infra.modelgateway.DefaultModelGateway;
import com.agentframework.infra.modelgateway.EchoModelProvider;
import com.agentframework.infra.modelgateway.ModelGateway;
import com.agentframework.infra.modelgateway.ModelProvider;
import com.agentframework.infra.storage.InMemoryEventBus;
import com.agentframework.infra.storage.InMemoryCheckpointStore;
import com.agentframework.infra.storage.InMemorySessionStore;
import com.agentframework.infra.storage.InMemorySlotStore;
import com.agentframework.infra.storage.InMemoryWorkspaceSnapshotStore;
import com.agentframework.runtime.event.EventBus;
import com.agentframework.runtime.persistence.SessionStore;
import com.agentframework.runtime.persistence.CheckpointStore;
import com.agentframework.runtime.persistence.SlotStore;
import com.agentframework.runtime.persistence.WorkspaceSnapshotStore;
import com.agentframework.runtime.workspace.WorkspaceTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 引擎装配器：把定义、扩展、基础设施与内核组件组装成可运行的 {@link Engine}。
 *
 * <p>默认装配全部落到内存实现（内存存储、内存事件总线、回声模型），因此“零依赖跑通一条执行链”
 * 是默认体验；把任意一项替换为外部实现都只是换一行配置。</p>
 */
public final class EngineBuilder {

    private EngineConfig config = EngineConfig.defaults();
    private DefinitionSource definitions;
    private SessionStore sessionStore;
    private SlotStore slotStore;
    private WorkspaceSnapshotStore snapshotStore;
    private CheckpointStore checkpointStore;
    private EventBus eventBus;
    private Tracer tracer;
    private Metrics metrics;
    private CacheStore cacheStore;
    private ExtensionRegistry extensions = new DefaultExtensionRegistry();
    private PromptProvider promptProvider;
    private ToolRegistry toolRegistry;
    private ModelGateway modelGateway;
    private Scheduler scheduler;
    private PermissionSet grantedPermissions = PermissionSet.all();
    private String defaultModelProvider = "echo";
    private int circuitFailureThreshold = 5;
    private Duration circuitOpenDuration = Duration.ofSeconds(30);

    private final List<AgentDefinition> agentDefinitions = new ArrayList<>();
    private final List<WorkflowDefinition> workflowDefinitions = new ArrayList<>();
    private final List<PromptDefinition> promptDefinitions = new ArrayList<>();
    private final List<ToolDefinition> toolDefinitions = new ArrayList<>();
    private final List<Tool> tools = new ArrayList<>();
    private final List<ModelProvider> modelProviders = new ArrayList<>();
    private final List<Guard> guards = new ArrayList<>();
    private final List<Filter<?, ?>> filters = new ArrayList<>();
    private final List<Interceptor> interceptors = new ArrayList<>();
    private final List<WorkspaceTemplate> templates = new ArrayList<>();
    private final List<PromptRenderer> renderers = new ArrayList<>();
    private final Map<String, NodeExecutor> namedExecutors = new LinkedHashMap<>();
    private final Map<Tool, ToolDefinition> toolDefinitionsByTool = new LinkedHashMap<>();
    private final List<PolicyComponent> extraComponents = new ArrayList<>();

    private EngineBuilder() {
    }

    /**
     * @return 新的装配器
     */
    public static EngineBuilder create() {
        return new EngineBuilder();
    }

    /**
     * @param config 引擎配置
     * @return 当前装配器
     */
    public EngineBuilder config(EngineConfig config) {
        this.config = config == null ? EngineConfig.defaults() : config;
        return this;
    }

    /**
     * @param definitions 定义来源
     * @return 当前装配器
     */
    public EngineBuilder definitions(DefinitionSource definitions) {
        this.definitions = definitions;
        return this;
    }

    /**
     * @param definition Agent 定义
     * @return 当前装配器
     */
    public EngineBuilder agent(AgentDefinition definition) {
        agentDefinitions.add(definition);
        return this;
    }

    /**
     * @param workflow 工作流定义
     * @return 当前装配器
     */
    public EngineBuilder workflow(WorkflowDefinition workflow) {
        workflowDefinitions.add(workflow);
        return this;
    }

    /**
     * @param prompt Prompt 定义
     * @return 当前装配器
     */
    public EngineBuilder prompt(PromptDefinition prompt) {
        promptDefinitions.add(prompt);
        return this;
    }

    /**
     * @param definition 工具定义
     * @return 当前装配器
     */
    public EngineBuilder toolDefinition(ToolDefinition definition) {
        toolDefinitions.add(definition);
        return this;
    }

    /**
     * @param tool 工具实现
     * @return 当前装配器
     */
    public EngineBuilder tool(Tool tool) {
        tools.add(tool);
        return this;
    }

    /**
     * @param definition 工具定义
     * @param tool       工具实现
     * @return 当前装配器
     */
    public EngineBuilder tool(ToolDefinition definition, Tool tool) {
        tools.add(tool);
        toolDefinitionsByTool.put(tool, definition);
        return this;
    }

    /**
     * @param provider 模型提供方
     * @return 当前装配器
     */
    public EngineBuilder modelProvider(ModelProvider provider) {
        if (provider != null) {
            modelProviders.add(provider);
            if (defaultModelProvider == null || "echo".equals(defaultModelProvider)) {
                defaultModelProvider = provider.id();
            }
        }
        return this;
    }

    /**
     * @param providerId 默认模型提供方标识
     * @return 当前装配器
     */
    public EngineBuilder defaultModelProvider(String providerId) {
        this.defaultModelProvider = providerId;
        return this;
    }

    /**
     * @param guard 守卫
     * @return 当前装配器
     */
    public EngineBuilder guard(Guard guard) {
        guards.add(guard);
        return this;
    }

    /**
     * @param filter 过滤器
     * @return 当前装配器
     */
    public EngineBuilder filter(Filter<?, ?> filter) {
        filters.add(filter);
        return this;
    }

    /**
     * @param interceptor 拦截器
     * @return 当前装配器
     */
    public EngineBuilder interceptor(Interceptor interceptor) {
        interceptors.add(interceptor);
        return this;
    }

    /**
     * 注册守卫并显式指定激活语义。
     *
     * @param name       注册名
     * @param activation 激活语义
     * @param guard      守卫实现
     * @return 当前装配器
     */
    public EngineBuilder component(String name, Activation activation, Guard guard) {
        extraComponents.add(new PolicyComponent(PolicyKind.GUARD, name, activation, guard, null));
        return this;
    }

    /**
     * 注册过滤器并显式指定激活语义。
     *
     * @param name       注册名
     * @param activation 激活语义
     * @param filter     过滤器实现
     * @return 当前装配器
     */
    public EngineBuilder component(String name, Activation activation, Filter<?, ?> filter) {
        extraComponents.add(new PolicyComponent(PolicyKind.FILTER, name, activation, filter, null));
        return this;
    }

    /**
     * 注册拦截器并显式指定激活语义。
     *
     * @param name        注册名
     * @param activation  激活语义
     * @param interceptor 拦截器实现
     * @return 当前装配器
     */
    public EngineBuilder component(String name, Activation activation, Interceptor interceptor) {
        extraComponents.add(new PolicyComponent(PolicyKind.INTERCEPTOR, name, activation, interceptor, null));
        return this;
    }

    /**
     * 注册参数化组件工厂：引用参数由节点 / 工作流 / Agent 作用域提供。
     *
     * @param kind       组件类别
     * @param name       注册名
     * @param activation 激活语义
     * @param factory    工厂
     * @return 当前装配器
     */
    public EngineBuilder factory(PolicyKind kind, String name, Activation activation, PolicyFactory factory) {
        extraComponents.add(new PolicyComponent(kind, name, activation, null, factory));
        return this;
    }

    /**
     * 注册迭代上限守卫：计数键为节点 id，达到上限时中断循环并沿前向边继续。
     *
     * @param name          注册名，供节点 / Region / 工作流引用
     * @param maxIterations 最大迭代次数，≤0 表示不限制
     * @return 当前装配器
     */
    public EngineBuilder loopGuard(String name, int maxIterations) {
        return component(name, Activation.DEFAULT_OFF, Guards.maxIterations(name, maxIterations));
    }

    /**
     * @param name     注册名
     * @param executor 自定义节点执行器
     * @return 当前装配器
     */
    public EngineBuilder nodeExecutor(String name, NodeExecutor executor) {
        namedExecutors.put(name, executor);
        return this;
    }

    /**
     * @param template 工作区模板
     * @return 当前装配器
     */
    public EngineBuilder workspaceTemplate(WorkspaceTemplate template) {
        templates.add(template);
        return this;
    }

    /**
     * @param renderer Prompt 渲染器
     * @return 当前装配器
     */
    public EngineBuilder renderer(PromptRenderer renderer) {
        renderers.add(renderer);
        return this;
    }

    /**
     * @param type 扩展点类型
     * @param id   注册名
     * @param impl 实现
     * @param <T>  扩展点类型
     * @return 当前装配器
     */
    public <T> EngineBuilder extension(Class<T> type, String id, T impl) {
        extensions.register(type, id, impl);
        return this;
    }

    /**
     * @param registry 扩展注册表
     * @return 当前装配器
     */
    public EngineBuilder extensions(ExtensionRegistry registry) {
        this.extensions = registry == null ? new DefaultExtensionRegistry() : registry;
        return this;
    }

    /**
     * @param store 会话存储
     * @return 当前装配器
     */
    public EngineBuilder sessionStore(SessionStore store) {
        this.sessionStore = store;
        return this;
    }

    /**
     * @param store 槽位存储
     * @return 当前装配器
     */
    public EngineBuilder slotStore(SlotStore store) {
        this.slotStore = store;
        return this;
    }

    /**
     * @param store 工作区快照存储
     * @return 当前装配器
     */
    public EngineBuilder snapshotStore(WorkspaceSnapshotStore store) {
        this.snapshotStore = store;
        return this;
    }

    /**
     * 自定义检查点历史存储；默认使用内存实现（每会话保留最近 50 步）。
     *
     * @param store 检查点存储
     * @return 当前装配器
     */
    public EngineBuilder checkpointStore(CheckpointStore store) {
        this.checkpointStore = store;
        return this;
    }

    /**
     * @param events 事件总线
     * @return 当前装配器
     */
    public EngineBuilder events(EventBus events) {
        this.eventBus = events;
        return this;
    }

    /**
     * @param tracer 追踪器
     * @return 当前装配器
     */
    public EngineBuilder tracer(Tracer tracer) {
        this.tracer = tracer;
        return this;
    }

    /**
     * @param metrics 指标采集器
     * @return 当前装配器
     */
    public EngineBuilder metrics(Metrics metrics) {
        this.metrics = metrics;
        return this;
    }

    /**
     * @param cacheStore 缓存存储
     * @return 当前装配器
     */
    public EngineBuilder cache(CacheStore cacheStore) {
        this.cacheStore = cacheStore;
        return this;
    }

    /**
     * @param promptProvider Prompt 资产来源
     * @return 当前装配器
     */
    public EngineBuilder promptProvider(PromptProvider promptProvider) {
        this.promptProvider = promptProvider;
        return this;
    }

    /**
     * @param toolRegistry 工具注册表
     * @return 当前装配器
     */
    public EngineBuilder toolRegistry(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
        return this;
    }

    /**
     * @param modelGateway 模型网关
     * @return 当前装配器
     */
    public EngineBuilder modelGateway(ModelGateway modelGateway) {
        this.modelGateway = modelGateway;
        return this;
    }

    /**
     * @param scheduler 调度器
     * @return 当前装配器
     */
    public EngineBuilder scheduler(Scheduler scheduler) {
        this.scheduler = scheduler;
        return this;
    }

    /**
     * @param permissions 宿主授予插件的权限
     * @return 当前装配器
     */
    public EngineBuilder permissions(PermissionSet permissions) {
        this.grantedPermissions = permissions == null ? PermissionSet.none() : permissions;
        return this;
    }

    /**
     * @param threshold 熔断失败阈值
     * @param openFor   熔断冷却时长
     * @return 当前装配器
     */
    public EngineBuilder circuitBreaker(int threshold, Duration openFor) {
        this.circuitFailureThreshold = threshold;
        this.circuitOpenDuration = openFor;
        return this;
    }

    /**
     * 装配引擎。
     *
     * @return 可直接使用的引擎实例
     */
    public Engine build() {
        DefinitionSource definitionSource = resolveDefinitions();
        SessionStore sessions = sessionStore == null ? new InMemorySessionStore() : sessionStore;
        SlotStore slotStoreResolved = slotStore == null ? new InMemorySlotStore() : slotStore;
        WorkspaceSnapshotStore snapshots = snapshotStore == null
                ? new InMemoryWorkspaceSnapshotStore()
                : snapshotStore;
        EventBus events = eventBus == null ? new InMemoryEventBus() : eventBus;
        Tracer tracerResolved = tracer == null ? new SimpleTracer() : tracer;
        Metrics metricsResolved = metrics == null ? new InMemoryMetrics() : metrics;
        CacheStore cacheResolved = cacheStore == null ? new InMemoryCacheStore() : cacheStore;
        Scheduler schedulerResolved = scheduler == null ? new DefaultScheduler() : scheduler;

        PolicyCatalog policyCatalog = new PolicyCatalog();
        MiddlewarePipeline middleware = buildMiddleware(tracerResolved, metricsResolved, cacheResolved, events, policyCatalog);
        PromptProvider provider = buildPromptProvider(definitionSource);
        PolicyResolver policyResolver = new PolicyResolver(policyCatalog,
                (promptId, promptVersion) -> provider.find(promptId, promptVersion).orElse(null));
        ToolRegistry toolsResolved = buildToolRegistry();
        PromptManager promptManager = new DefaultPromptManager(provider, renderers, middleware, events);
        ModelGateway gateway = buildModelGateway(metricsResolved);
        AgentManager agentManager = new DefaultAgentManager(definitionSource,
                new AgentValidator(provider, toolsResolved, policyCatalog));
        CheckpointStore checkpoints = checkpointStore == null ? new InMemoryCheckpointStore() : checkpointStore;
        PersistenceManager persistence = new PersistenceManager(sessions, slotStoreResolved, snapshots,
                tracerResolved, checkpoints);
        QuotaEnforcer quotaEnforcer = new QuotaEnforcer();
        RegionMetricsCollector regionMetrics = new RegionMetricsCollector();
        ToolExecutor toolExecutor = new DefaultToolExecutor(toolsResolved, middleware, metricsResolved, events,
                quotaEnforcer);
        NodeExecutorRegistry nodeExecutors = buildNodeExecutors(definitionSource, promptManager, gateway,
                toolsResolved, toolExecutor, middleware, events);
        WorkflowRuntime workflowRuntime = new DefaultWorkflowRuntime(nodeExecutors, middleware, tracerResolved,
                metricsResolved, events, config, quotaEnforcer, persistence::checkpoint, policyResolver,
                regionMetrics);
        DefaultContextManager contexts = new DefaultContextManager(sessions, slotStoreResolved, snapshots);
        templates.forEach(contexts::registerTemplate);
        PluginRuntime plugins = new PluginRuntime(extensions, new PermissionChecker(grantedPermissions));
        RecoveryManager recovery = new RecoveryManager(contexts, schedulerResolved);

        EngineServices services = new EngineServices(config, definitionSource, agentManager, contexts, promptManager,
                gateway, toolsResolved, toolExecutor, middleware, nodeExecutors, workflowRuntime, tracerResolved,
                metricsResolved, events, cacheResolved, extensions, quotaEnforcer, plugins, persistence, recovery,
                schedulerResolved, policyResolver, policyCatalog, regionMetrics);
        Engine engine = new DefaultEngine(services);
        recovery.attach(engine);
        return engine;
    }

    /**
     * @return 定义来源（未显式提供时构建内存仓库）
     */
    private DefinitionSource resolveDefinitions() {
        InMemoryDefinitionSource source = new InMemoryDefinitionSource();
        if (definitions instanceof InMemoryDefinitionSource inMemory) {
            workflowDefinitions.forEach(inMemory::add);
            promptDefinitions.forEach(inMemory::add);
            toolDefinitions.forEach(inMemory::add);
            agentDefinitions.forEach(inMemory::add);
            return inMemory;
        }
        if (definitions != null) {
            return definitions;
        }
        workflowDefinitions.forEach(source::add);
        promptDefinitions.forEach(source::add);
        toolDefinitions.forEach(source::add);
        agentDefinitions.forEach(source::add);
        return source;
    }

    /**
     * 构建中间件管道并注册默认横切能力。
     *
     * @param tracer  追踪器
     * @param metrics 指标
     * @param cache   缓存
     * @param events  事件总线
     * @param catalog 策略目录，注册的组件同时进入目录
     * @return 中间件管道
     */
    private MiddlewarePipeline buildMiddleware(Tracer tracer, Metrics metrics, CacheStore cache, EventBus events,
                                               PolicyCatalog catalog) {
        DefaultMiddlewarePipeline pipeline = new DefaultMiddlewarePipeline();
        Guards.AllowAll allowAll = new Guards.AllowAll();
        pipeline.register(allowAll);
        catalog.registerGuard(allowAll.name(), Activation.DEFAULT_ON, allowAll);
        guards.forEach(guard -> {
            pipeline.register(guard);
            catalog.registerGuard(guard.name(), Activation.DEFAULT_ON, guard);
        });
        filters.forEach(filter -> {
            pipeline.register(filter);
            catalog.registerFilter(filter.name(), Activation.DEFAULT_ON, filter);
        });
        interceptors.forEach(interceptor -> {
            pipeline.register(interceptor);
            catalog.registerInterceptor(interceptor.name(), Activation.DEFAULT_ON, interceptor);
        });
        if (config.traceEnabled()) {
            Interceptors.Trace trace = new Interceptors.Trace(tracer);
            pipeline.register(trace);
            catalog.registerInterceptor(trace.name(), Activation.DEFAULT_ON, trace);
        }
        if (config.cacheEnabled()) {
            Interceptors.Cache cacheInterceptor = new Interceptors.Cache(cache, new ContentHashCacheKeyBuilder(),
                    events, metrics);
            pipeline.register(cacheInterceptor);
            catalog.registerInterceptor(cacheInterceptor.name(), Activation.DEFAULT_ON, cacheInterceptor);
        }
        if (config.metricsEnabled()) {
            Interceptors.MetricsInterceptor metricsInterceptor = new Interceptors.MetricsInterceptor(metrics, "agent");
            pipeline.register(metricsInterceptor);
            catalog.registerInterceptor(metricsInterceptor.name(), Activation.DEFAULT_ON, metricsInterceptor);
        }
        Interceptors.Retry retry = new Interceptors.Retry();
        Interceptors.Timeout timeout = new Interceptors.Timeout();
        Interceptors.CircuitBreaker circuitBreaker = new Interceptors.CircuitBreaker(circuitFailureThreshold,
                circuitOpenDuration);
        Interceptors.Logging logging = new Interceptors.Logging();
        List.of(retry, timeout, circuitBreaker, logging).forEach(interceptor -> {
            pipeline.register(interceptor);
            catalog.registerInterceptor(interceptor.name(), Activation.DEFAULT_ON, interceptor);
        });
        for (PolicyComponent component : extraComponents) {
            registerComponent(catalog, component);
            if (component.defaultOn()) {
                registerIntoPipeline(pipeline, component);
            }
        }
        return pipeline;
    }

    /**
     * 把额外组件登记进目录。
     *
     * @param catalog   策略目录
     * @param component 目录条目
     */
    private void registerComponent(PolicyCatalog catalog, PolicyComponent component) {
        if (component.factory() != null) {
            catalog.registerFactory(component.kind(), component.name(), component.activation(), component.factory());
            return;
        }
        switch (component.kind()) {
            case GUARD -> catalog.registerGuard(component.name(), component.activation(), (Guard) component.instance());
            case FILTER ->
                    catalog.registerFilter(component.name(), component.activation(), (Filter<?, ?>) component.instance());
            case INTERCEPTOR -> catalog.registerInterceptor(component.name(), component.activation(),
                    (Interceptor) component.instance());
        }
    }

    /**
     * 把默认生效的额外组件登记进管道，保证旧执行路径与解析路径一致。
     *
     * @param pipeline  中间件管道
     * @param component 目录条目
     */
    private void registerIntoPipeline(MiddlewarePipeline pipeline, PolicyComponent component) {
        if (component.instance() == null) {
            return;
        }
        switch (component.kind()) {
            case GUARD -> pipeline.register((Guard) component.instance());
            case FILTER -> pipeline.register((Filter<?, ?>) component.instance());
            case INTERCEPTOR -> pipeline.register((Interceptor) component.instance());
        }
    }

    /**
     * @return 工具注册表
     */
    private ToolRegistry buildToolRegistry() {
        DefaultToolRegistry registry = toolRegistry instanceof DefaultToolRegistry defaultRegistry
                ? defaultRegistry
                : new DefaultToolRegistry();
        if (toolRegistry != null && toolRegistry != registry) {
            return toolRegistry;
        }
        tools.forEach(tool -> {
            ToolDefinition definition = toolDefinitionsByTool.get(tool);
            if (definition != null) {
                registry.register(definition, tool);
            } else {
                registry.register(tool);
            }
        });
        toolDefinitions.stream()
                .filter(definition -> registry.definition(definition.id()).isEmpty())
                .forEach(definition -> tools.stream()
                        .filter(tool -> tool.id().equals(definition.id()))
                        .findFirst()
                        .ifPresent(tool -> registry.register(definition, tool)));
        return registry;
    }

    /**
     * @param definitionSource 定义来源
     * @return Prompt 资产来源
     */
    private PromptProvider buildPromptProvider(DefinitionSource definitionSource) {
        if (promptProvider != null) {
            return promptProvider;
        }
        InMemoryPromptProvider provider = new InMemoryPromptProvider(definitionSource);
        promptDefinitions.forEach(provider::register);
        return provider;
    }

    /**
     * @param metrics 指标
     * @return 模型网关
     */
    private ModelGateway buildModelGateway(Metrics metrics) {
        if (modelGateway != null) {
            return modelGateway;
        }
        List<ModelProvider> providers = new ArrayList<>(modelProviders);
        if (providers.isEmpty()) {
            providers.add(new EchoModelProvider());
        }
        String defaultId = defaultModelProvider == null ? providers.get(0).id() : defaultModelProvider;
        DefaultModelGateway gateway = new DefaultModelGateway(defaultId, metrics);
        providers.forEach(gateway::register);
        return gateway;
    }

    /**
     * 注册全部内置节点执行器。
     *
     * @param definitionSource 定义来源
     * @param prompts          Prompt 管理器
     * @param gateway          模型网关
     * @param tools            工具注册表
     * @param toolExecutor     工具执行器
     * @param events           事件总线
     * @return 节点执行器注册表
     */
    private NodeExecutorRegistry buildNodeExecutors(DefinitionSource definitionSource, PromptManager prompts,
                                                    ModelGateway gateway, ToolRegistry tools, ToolExecutor toolExecutor, MiddlewarePipeline middleware,
                                                    EventBus events) {
        NodeExecutorRegistry registry = new NodeExecutorRegistry();
        registry.register(new ConditionNodeExecutor());
        registry.register(new HumanNodeExecutor());
        registry.register(new ParallelNodeExecutor(config));
        registry.register(new ToolNodeExecutor(toolExecutor, tools));
        registry.register(new LlmNodeExecutor(prompts, gateway, middleware, tools, events));
        registry.register(new SubWorkflowNodeExecutor(definitionSource, config));
        registry.register(new CustomNodeExecutor(registry::resolve));
        namedExecutors.forEach(registry::register);
        return registry;
    }

    /**
     * @return 已登记的 Agent 定义数量
     */
    public int agentCount() {
        return agentDefinitions.size();
    }

    /**
     * @return 已登记的工具数量
     */
    public int toolCount() {
        return tools.size();
    }

    /**
     * @return 已登记的扩展数量
     */
    public int extensionCount() {
        return extensions.metas().size();
    }

    /**
     * @return 当前配置
     */
    public EngineConfig configView() {
        return config;
    }

    /**
     * @return 已登记的工作区模板数量
     */
    public int templateCount() {
        return templates.size();
    }

    /**
     * @return 已登记的自定义执行器名称
     */
    public List<String> customExecutorNames() {
        return List.copyOf(namedExecutors.keySet());
    }

}
