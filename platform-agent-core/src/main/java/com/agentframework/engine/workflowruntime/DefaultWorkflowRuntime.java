package com.agentframework.engine.workflowruntime;

import com.agentframework.crosscutting.filter.FilterContext;
import com.agentframework.crosscutting.filter.FilterPhase;
import com.agentframework.crosscutting.guard.GuardContext;
import com.agentframework.crosscutting.guard.GuardDecision;
import com.agentframework.crosscutting.guard.GuardPhase;
import com.agentframework.crosscutting.guard.LoopBreakException;
import com.agentframework.crosscutting.interceptor.InterceptorAttributes;
import com.agentframework.crosscutting.interceptor.InterceptorContext;
import com.agentframework.crosscutting.interceptor.InterceptorPhase;
import com.agentframework.crosscutting.interceptor.Invocation;
import com.agentframework.crosscutting.metrics.Metrics;
import com.agentframework.crosscutting.trace.Span;
import com.agentframework.crosscutting.trace.SpanKind;
import com.agentframework.crosscutting.trace.Tracer;
import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.policy.QuotaPolicy;
import com.agentframework.definition.policy.TimeoutPolicy;
import com.agentframework.definition.region.RegionDefinition;
import com.agentframework.definition.workflow.Edge;
import com.agentframework.definition.workflow.Expression;
import com.agentframework.definition.workflow.SlotScope;
import com.agentframework.definition.workflow.SlotSpec;
import com.agentframework.definition.workflow.WorkflowDefinition;
import com.agentframework.engine.agentmanager.Agent;
import com.agentframework.engine.core.BranchInvoker;
import com.agentframework.engine.core.EngineConfig;
import com.agentframework.engine.core.ExecutionTraceStep;
import com.agentframework.engine.core.NodeContext;
import com.agentframework.engine.core.NodeResult;
import com.agentframework.engine.core.RunOutcome;
import com.agentframework.engine.core.WorkflowExecution;
import com.agentframework.engine.middleware.MiddlewarePipeline;
import com.agentframework.engine.policy.PolicyAttributes;
import com.agentframework.engine.policy.PolicyGuards;
import com.agentframework.engine.policy.PolicyResolver;
import com.agentframework.engine.policy.ResolvedPolicy;
import com.agentframework.engine.policy.ResolvedPolicyIndex;
import com.agentframework.engine.policy.RegionMetricsCollector;
import com.agentframework.extension.permission.Quota;
import com.agentframework.extension.permission.QuotaEnforcer;
import com.agentframework.infra.modelgateway.Usage;
import com.agentframework.runtime.event.Event;
import com.agentframework.runtime.event.EventBus;
import com.agentframework.runtime.event.Topics;
import com.agentframework.runtime.session.Cursor;
import com.agentframework.runtime.session.Message;
import com.agentframework.runtime.session.MutableSession;
import com.agentframework.runtime.session.Session;
import com.agentframework.runtime.session.SessionState;
import com.agentframework.runtime.slot.Slots;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;

/**
 * 默认工作流运行时：把游标、节点执行器、中间件管道与配额串成一条主循环。
 *
 * <p>主循环每一步都会：校验配额 → 执行节点（含守卫/过滤器/拦截器）→ 写回槽位 → 计算下一个节点 →
 * 保存检查点。它同时实现 {@link BranchInvoker}，因此并行节点与子工作流节点复用同一套节点执行逻辑，
 * 不会绕过任何横切能力。</p>
 */
public final class DefaultWorkflowRuntime implements WorkflowRuntime, BranchInvoker {

    /** 节点结果中标记循环中断原因的元数据键。 */
    public static final String LOOP_BREAK_METADATA = "loopBreak";

    private final NodeExecutorRegistry nodeExecutorRegistry;
    private final MiddlewarePipeline middleware;
    private final Tracer tracer;
    private final Metrics metrics;
    private final EventBus events;
    private final EngineConfig config;
    private final QuotaEnforcer quotaEnforcer;
    private final WorkflowCheckpoint checkpoint;
    private final PolicyResolver policyResolver;
    private final RegionMetricsCollector regionMetrics;
    private final Map<String, ResolvedPolicyIndex> policyIndexCache = new ConcurrentHashMap<>();

    /**
     * @param nodeExecutorRegistry      节点执行器注册表
     * @param middleware    中间件管道，可为 null
     * @param tracer        追踪器，可为 null
     * @param metrics       指标采集器，可为 null
     * @param events        事件总线，可为 null
     * @param config        引擎配置
     * @param quotaEnforcer 配额执行器，可为 null
     * @param checkpoint    检查点回调，可为 null
     */
    public DefaultWorkflowRuntime(NodeExecutorRegistry nodeExecutorRegistry, MiddlewarePipeline middleware, Tracer tracer,
                                  Metrics metrics, EventBus events, EngineConfig config, QuotaEnforcer quotaEnforcer,
                                  WorkflowCheckpoint checkpoint) {
        this(nodeExecutorRegistry, middleware, tracer, metrics, events, config, quotaEnforcer, checkpoint, null);
    }

    /**
     * @param nodeExecutorRegistry       节点执行器注册表
     * @param middleware     中间件管道，可为 null
     * @param tracer         追踪器，可为 null
     * @param metrics        指标采集器，可为 null
     * @param events         事件总线，可为 null
     * @param config         引擎配置
     * @param quotaEnforcer  配额执行器，可为 null
     * @param checkpoint     检查点回调，可为 null
     * @param policyResolver 策略解析器，可为 null 表示退化为全局注册语义
     */
    public DefaultWorkflowRuntime(NodeExecutorRegistry nodeExecutorRegistry, MiddlewarePipeline middleware, Tracer tracer,
                                  Metrics metrics, EventBus events, EngineConfig config, QuotaEnforcer quotaEnforcer,
                                  WorkflowCheckpoint checkpoint, PolicyResolver policyResolver) {
        this(nodeExecutorRegistry, middleware, tracer, metrics, events, config, quotaEnforcer, checkpoint, policyResolver, null);
    }

    /**
     * @param nodeExecutorRegistry       节点执行器注册表
     * @param middleware     中间件管道，可为 null
     * @param tracer         追踪器，可为 null
     * @param metrics        指标采集器，可为 null
     * @param events         事件总线，可为 null
     * @param config         引擎配置
     * @param quotaEnforcer  配额执行器，可为 null
     * @param checkpoint     检查点回调，可为 null
     * @param policyResolver 策略解析器，可为 null 表示退化为全局注册语义
     * @param regionMetrics  Region 指标采集器，可为 null
     */
    public DefaultWorkflowRuntime(NodeExecutorRegistry nodeExecutorRegistry, MiddlewarePipeline middleware, Tracer tracer,
                                  Metrics metrics, EventBus events, EngineConfig config, QuotaEnforcer quotaEnforcer,
                                  WorkflowCheckpoint checkpoint, PolicyResolver policyResolver, RegionMetricsCollector regionMetrics) {
        this.nodeExecutorRegistry = nodeExecutorRegistry;
        this.middleware = middleware;
        this.tracer = tracer;
        this.metrics = metrics;
        this.events = events;
        this.config = config == null ? EngineConfig.defaults() : config;
        this.quotaEnforcer = quotaEnforcer;
        this.checkpoint = checkpoint;
        this.policyResolver = policyResolver;
        this.regionMetrics = regionMetrics;
    }

    @Override
    public RunOutcome run(WorkflowExecution execution) {
        Session session = execution.session();
        Slots slots = execution.slots();
        WorkflowDefinition workflow = execution.workflow();
        Cursor cursor = session.cursor();
        if (!cursor.started()) {
            cursor = Cursor.at(workflow.entryNode().id());
            updateCursor(session, cursor);
        }
        // 初始检查点
        save(session, cursor, slots);
        List<String> visited = new ArrayList<>();
        List<Message> messages = new ArrayList<>();
        // 逐步执行轨迹
        List<ExecutionTraceStep> trace = new ArrayList<>();
        int index = 0;
        // 初始配额使用
        Usage usage = Usage.zero();
        ResolvedPolicyIndex policyIndex = index(execution);
        long startNanos = System.nanoTime();

        while (cursor.nodeId() != null) {
            if (visited.size() >= config.maxSteps()) {
                return new RunOutcome(SessionState.FAILED, "", cursor, messages, usage, visited, null,
                        "超过最大步数限制：" + config.maxSteps(), trace);
            }
            // 取消检查（节点边界）：会话被外部置为 CANCELLED（用户点「停止生成」）→ 立即收尾。
            // 正在执行的节点打断不了，但**不会再开下一个节点**，也不会把中止后的结果
            // 当成一次正常收尾落库——否则用户"停止"了，系统照样产出一份结论。
            // 检查放在这里而不是起一个中断线程：节点边界是天然的、无锁的一致性点。
            if (session.state() == SessionState.CANCELLED) {
                save(session, cursor, slots);
                return new RunOutcome(SessionState.CANCELLED, "", cursor, messages, usage, visited, null,
                        "会话已取消", trace);
            }
            NodeDefinition node = workflow.node(cursor.nodeId()).orElse(null);
            if (node == null) {
                return new RunOutcome(SessionState.FAILED, "", cursor, messages, usage, visited, null,
                        "游标指向不存在的节点：" + cursor.nodeId(), trace);
            }
            String arrivedVia = cursor.lastEdge() == null ? "" : cursor.lastEdge();
            ResolvedPolicy resolved = policyIndex == null ? null : policyIndex.node(node.id());
            cursor = this.countIteration(cursor, node, resolved);
            updateCursor(session, cursor);
            writeCounterSlot(slots, resolved, cursor);
            NodeContext context = new NodeContext(execution.agent(), session, workflow, cursor, slots,
                    execution.workspace(), execution.input(), execution.trace(), execution.parentSpan(), this,
                    policyIndex == null ? null : Map.of(PolicyAttributes.INDEX, policyIndex, PolicyAttributes.RESOLVED,
                            resolved));
            consumeQuota(execution.agent(), session.id(), resolved);
            checkWallTime(execution.agent(), session.id(), startNanos, resolved);
            long nodeStartNanos = System.nanoTime();
            NodeResult result = this.invokeNode(node, context);
            long nodeDurationMs = (System.nanoTime() - nodeStartNanos) / 1_000_000L;
            visited.add(node.id());
            if (!result.slotWrites().isEmpty()) {
                slots.putAll(expandRegionSlotWrites(workflow, node.id(), result.slotWrites()));
            }
            messages.addAll(result.messages());
            Usage nodeUsage = usageOf(result);
            usage = usage.plus(nodeUsage);
            consumeTokens(execution.agent(), session.id(), nodeUsage, resolved);
            if (regionMetrics != null) {
                regionMetrics.record(session.id(), resolved, node.type(), nodeDurationMs, nodeUsage.total(),
                        iterationOf(cursor, resolved), result.isSuspended());
            }
            cursor = observeConvergence(cursor, node, resolved, slots);
            updateCursor(session, cursor);
            String breakReason = result.metadata().get(LOOP_BREAK_METADATA) instanceof String reason ? reason : null;
            if (breakReason == null) {
                breakReason = convergenceBreak(cursor, node, resolved);
            }
            if (breakReason != null) {
                publish(session, Topics.LOOP_BREAK, node.id(), breakReason);
            }

            if (result.isSuspended()) {
                save(session, cursor, slots);
                trace.add(traceStep(++index, node, result, arrivedVia, nodeDurationMs, nodeUsage,
                        ExecutionTraceStep.ROUTE_SUSPENDED, null, null));
                return new RunOutcome(SessionState.SUSPENDED, result.output(), cursor, messages, usage, visited,
                        node.id(), null, trace);
            }
            if (result.isFailed()) {
                trace.add(traceStep(++index, node, result, arrivedVia, nodeDurationMs, nodeUsage,
                        ExecutionTraceStep.ROUTE_FAILED, null, null));
                return new RunOutcome(SessionState.FAILED, "", cursor, messages, usage, visited, null,
                        result.error(), trace);
            }
            Routing routing = resolveNext(result, node, workflow, slots, session, resolved, breakReason);
            String routeDetail = breakReason == null ? routing.detail()
                    : "loop break: " + breakReason + (routing.detail() == null ? "" : "; " + routing.detail());
            trace.add(traceStep(++index, node, result, arrivedVia, nodeDurationMs, nodeUsage, routing.kind(),
                    routing.next(), routeDetail));
            if (routing.next() == null) {
                save(session, cursor, slots);
                return new RunOutcome(SessionState.COMPLETED, result.output(), cursor, messages, usage, visited,
                        null, null, trace);
            }
            cursor = cursor.advanceTo(routing.next()).arriveVia(node.id() + "->" + routing.next());
            updateCursor(session, cursor);
            save(session, cursor, slots);
        }
        return new RunOutcome(SessionState.COMPLETED, "", cursor, messages, usage, visited, null, null, trace);
    }

    @Override
    public NodeResult invokeNode(NodeDefinition node, NodeContext context) {
        Session session = context.session();
        ResolvedPolicy resolved = resolvedPolicy(context, node);
        if (middleware != null) {
            GuardContext before = guardContext(GuardPhase.BEFORE_NODE, session, node, context,
                    context.slots().asMap());
            try {
                if (resolved == null) {
                    middleware.requireGuard(GuardPhase.BEFORE_NODE, before);
                } else {
                    PolicyGuards.require(resolved, GuardPhase.BEFORE_NODE, before, events);
                }
            } catch (LoopBreakException breakLoop) {
                return breakResult(node, breakLoop.reason());
            }
        }
        Span span = this.startSpan(context, node, resolved);
        NodeContext scoped = context.withParentSpan(span == null ? context.parentSpan() : span);
        if (resolved != null) {
            scoped = scoped.withAttribute(PolicyAttributes.RESOLVED, resolved);
        }
        final NodeContext executionContext = scoped;
        long start = System.nanoTime();
        NodeResult result;
        String breakReason = null;
        boolean interrupted = false;
        try {
            if (middleware == null) {
                result = nodeExecutorRegistry.execute(node, scoped);
            } else {
                InterceptorContext interceptorContext = this.interceptorContext(session, node, scoped);
                Invocation<NodeResult> terminal = () -> nodeExecutorRegistry.execute(node, executionContext);
                result = resolved == null
                        ? middleware.intercept(InterceptorPhase.AROUND_NODE, interceptorContext, terminal)
                        : middleware.intercept(InterceptorPhase.AROUND_NODE, interceptorContext,
                                resolved.interceptors(), terminal);
            }
        } catch (LoopBreakException breakLoop) {
            result = NodeResult.completed(node.id(), "");
            breakReason = breakLoop.reason();
            interrupted = true;
        } catch (Exception e) {
            result = NodeResult.failed(node.id(), e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (result == null) {
            result = NodeResult.failed(node.id(), "节点执行器未返回结果");
        }
        if (middleware != null && !interrupted) {
            FilterContext filterContext = FilterContext.of(FilterPhase.AFTER_NODE_OUTPUT)
                    .withOwner(session.id(), node.id())
                    .withAttributes(Map.of("nodeType", node.type().name()));
            result = result.withOutput(resolved == null
                    ? middleware.filter(FilterPhase.AFTER_NODE_OUTPUT, result.output(), filterContext)
                    : middleware.filter(FilterPhase.AFTER_NODE_OUTPUT, result.output(), filterContext,
                            resolved.filters()));
            GuardContext after = guardContext(GuardPhase.AFTER_NODE, session, node, context, result);
            try {
                if (resolved == null) {
                    middleware.requireGuard(GuardPhase.AFTER_NODE, after);
                } else {
                    PolicyGuards.require(resolved, GuardPhase.AFTER_NODE, after, events);
                }
            } catch (LoopBreakException breakLoop) {
                breakReason = breakLoop.reason();
            }
        }
        if (breakReason != null) {
            result = result.withMetadata(LOOP_BREAK_METADATA, breakReason);
        }
        if (tracer != null) {
            if (result.isFailed()) {
                tracer.endSpan(span, new IllegalStateException(result.error()));
            } else {
                tracer.endSpan(span);
            }
        }
        if (metrics != null) {
            Map<String, Object> tags = metricTags(node, resolved);
            metrics.counter(result.isFailed() ? "node.failures" : "node.calls", 1L, tags);
            metrics.histogram("node.duration_ms", (System.nanoTime() - start) / 1_000_000.0, tags);
        }
        publish(session, result.isFailed() ? Topics.NODE_FAILED : Topics.NODE_COMPLETED,
                nodeEvent(node, result, (System.nanoTime() - start) / 1_000_000L));
        return result;
    }

    /**
     * 构建节点结束事件的结构化负载。
     *
     * @param node       节点定义
     * @param result     节点结果
     * @param durationMs 执行耗时（毫秒）
     * @return 事件负载
     */
    private Map<String, Object> nodeEvent(NodeDefinition node, NodeResult result, long durationMs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeId", node.id());
        payload.put("nodeType", node.type().name());
        payload.put("status", result.status().name());
        payload.put("durationMs", durationMs);
        if (!result.slotWrites().isEmpty()) {
            payload.put("slotWrites", result.slotWrites());
        }
        if (result.error() != null) {
            payload.put("error", result.error());
        }
        return payload;
    }

    /**
     * 构建单步执行轨迹。
     *
     * @param index       步号
     * @param node        节点定义
     * @param result      节点结果
     * @param arrivedVia  进入该节点的边
     * @param durationMs  节点耗时（毫秒）
     * @param usage       本步 token 用量
     * @param routeKind   离开方式
     * @param routeTo     路由目标
     * @param routeDetail 路由补充说明
     * @return 轨迹步
     */
    private ExecutionTraceStep traceStep(int index, NodeDefinition node, NodeResult result, String arrivedVia,
            long durationMs, Usage usage, String routeKind, String routeTo, String routeDetail) {
        return new ExecutionTraceStep(index, node.id(), node.type(), result.status(), arrivedVia, durationMs,
                result.output(), result.slotWrites(), usage, modelOf(result), toolCallOf(result), routeKind, routeTo,
                routeDetail, result.error());
    }

    /**
     * 路由决策：目标节点、离开方式与补充说明。
     *
     * @param next   目标节点 id，null 表示结束
     * @param kind   离开方式，取值见 {@link ExecutionTraceStep} 的 ROUTE 常量
     * @param detail 补充说明（选中的边、被拒原因等）
     */
    private record Routing(String next, String kind, String detail) {
    }

    /**
     * 计算下一个节点：按优先级取第一条条件成立的出边。
     *
     * @param workflow 工作流
     * @param node     当前节点
     * @param slots    当前槽位
     * @param session  当前会话
     * @return 下一个节点 id，null 表示结束
     */
    private String nextNode(WorkflowDefinition workflow, NodeDefinition node, Slots slots, Session session) {
        return nextNode(workflow, node, slots, session, null);
    }

    /**
     * 计算下一个节点，优先级为：动态边 → 确定性路由 → 静态出边。
     *
     * <p>动态边被拒绝时不中断执行，而是回退到静态路由，并发布审计事件与指标。</p>
     *
     * @param result      节点结果
     * @param node        当前节点
     * @param workflow    工作流
     * @param slots       槽位
     * @param session     会话
     * @param resolved    节点解析结果
     * @param breakReason 循环中断原因，可为 null
     * @return 路由决策：目标（null 表示结束）、方式与补充说明
     */
    private Routing resolveNext(NodeResult result, NodeDefinition node, WorkflowDefinition workflow, Slots slots,
            Session session, ResolvedPolicy resolved, String breakReason) {
        String rejected = null;
        String dynamic = result.dynamicNextNodeId();
        if (dynamic != null) {
            String rejection = rejectionReason(node, dynamic, workflow, resolved, breakReason);
            if (rejection == null) {
                publish(session, Topics.ROUTE_DYNAMIC, node.id(), node.id() + "->" + dynamic);
                if (metrics != null) {
                    metrics.counter("route.dynamic", 1L, Map.of("node", node.id()));
                }
                return new Routing(dynamic, ExecutionTraceStep.ROUTE_DYNAMIC, node.id() + "->" + dynamic);
            }
            publish(session, Topics.ROUTE_REJECTED, node.id(),
                    rejection + "：" + node.id() + "->" + dynamic);
            if (metrics != null) {
                metrics.counter("route.rejected", 1L, Map.of("node", node.id(), "reason", rejection));
            }
            rejected = rejection;
        }
        String next = result.nextNodeId() != null ? result.nextNodeId()
                : nextNode(workflow, node, slots, session, breakReason == null ? null : resolved);
        if (next == null) {
            return new Routing(null, ExecutionTraceStep.ROUTE_TERMINAL,
                    rejected == null ? null : "dynamic rejected: " + rejected);
        }
        String kind = result.nextNodeId() != null ? ExecutionTraceStep.ROUTE_DETERMINISTIC
                : ExecutionTraceStep.ROUTE_STATIC;
        String detail = node.id() + "->" + next + (rejected == null ? "" : " (dynamic rejected: " + rejected + ")");
        return new Routing(next, kind, detail);
    }

    /**
     * @return 动态目标被拒绝的原因，允许时返回 null
     */
    private String rejectionReason(NodeDefinition node, String target, WorkflowDefinition workflow,
            ResolvedPolicy resolved, String breakReason) {
        if (!config.dynamicRoutingEnabled()) {
            return "dynamic_routing_disabled";
        }
        if (!workflow.dynamicPolicy().allows(node.id(), target)) {
            return "target_not_allowed";
        }
        if (breakReason != null && resolved != null && resolved.inRegion()
                && workflow.regionOf(target).map(regionId -> regionId.equals(resolved.regionId())).orElse(false)) {
            return "loop_break_requires_exit";
        }
        return null;
    }

    /**
     * 计算下一个节点：按优先级取第一条条件成立的出边。
     *
     * @param workflow      工作流
     * @param node          当前节点
     * @param slots         当前槽位
     * @param session       当前会话
     * @return 下一个节点 id，null 表示结束
     */
    private String nextNode(WorkflowDefinition workflow, NodeDefinition node, Slots slots, Session session,
            ResolvedPolicy breaking) {
        if (node.isTerminal()) {
            return null;
        }
        Map<String, Object> variables = this.variables(slots);
        for (Edge edge : workflow.outgoing(node.id())) {
            if (breaking != null && shouldSkipOnBreak(workflow, edge, breaking)) {
                continue;
            }
            if (edge.isConditional() && !Expression.evaluate(edge.condition(), variables)) {
                continue;
            }
            if (edge.isGuarded() && middleware != null) {
                GuardDecision decision = middleware.guard(GuardPhase.BEFORE_NODE,
                        GuardContext.of(GuardPhase.BEFORE_NODE, edge)
                                .withSession(session.id(), session.tenantId())
                                .withOwner(null, node.id())
                                .withSlots(slots)
                                .withAttributes(Map.of("edge", edge.key(), "guardRef", edge.guardRef())));
                if (!decision.allowed()) {
                    continue;
                }
            }
            return edge.to();
        }
        return null;
    }

    /**
     * 判断循环中断后应跳过的边。
     *
     * <p>Region 声明循环时：跳过仍停留在同一 Region 内的边，只走通往区域外的出路；
     * 无循环声明时退化为跳过回边。</p>
     *
     * @param workflow 工作流
     * @param edge     候选边
     * @param breaking 触发中断的节点解析结果
     * @return 应跳过返回 true
     */
    private boolean shouldSkipOnBreak(WorkflowDefinition workflow, Edge edge, ResolvedPolicy breaking) {
        if (breaking.inLoop()) {
            return workflow.regionOf(edge.to())
                    .map(regionId -> regionId.equals(breaking.regionId()))
                    .orElse(false);
        }
        return isBackEdge(workflow, edge);
    }

    /**
     * 记录迭代次数：Region 声明循环时只在入口节点按循环标识计数，否则按节点计数。
     *
     * @param cursor   当前游标
     * @param node     当前节点
     * @param resolved 节点解析结果
     * @return 计数后的游标
     */
    private Cursor countIteration(Cursor cursor, NodeDefinition node, ResolvedPolicy resolved) {
        if (resolved != null && resolved.inLoop()) {
            if (!node.id().equals(resolved.loop().entry())) {
                return cursor;
            }
            String key = resolved.loopId();
            return cursor.withLoopCounter(key, cursor.loopCounters().getOrDefault(key, 0) + 1);
        }
        return cursor.withLoopCounter(node.id(), cursor.loopCounters().getOrDefault(node.id(), 0) + 1);
    }

    /**
     * 把循环计数写入声明的槽位，便于表达式与 Prompt 直接引用。
     *
     * @param slots    槽位容器
     * @param resolved 节点解析结果
     * @param cursor   当前游标
     */
    private void writeCounterSlot(Slots slots, ResolvedPolicy resolved, Cursor cursor) {
        if (resolved == null || !resolved.inLoop() || !resolved.loop().hasCounterSlot()) {
            return;
        }
        slots.put(resolved.loop().counterSlot(), cursor.loopCounters().getOrDefault(resolved.loopId(), 0));
    }

    /**
     * Region 局部槽模板短名展开：节点位于带 {@code slotPrefix} 的 Region 内、且写入键命中
     * {@code SlotScope.REGION} 模板短名时，改写为 {@code {prefix}_{name}} 物理槽；其余键原样保留。
     *
     * <p>配合 {@link NodeContext#variables()} 的短名别名，Region 内执行器读写都用短名，
     * 物理槽位与持久化快照保持全名，交付层与旧数据完全兼容。</p>
     *
     * @param workflow 工作流定义
     * @param nodeId   当前节点 id
     * @param writes   节点产出的槽位写入
     * @return 展开后的写入（无需展开时原样返回）
     */
    private Map<String, Object> expandRegionSlotWrites(WorkflowDefinition workflow, String nodeId,
            Map<String, Object> writes) {
        Set<String> templates = new HashSet<>();
        for (SlotSpec spec : workflow.slotsSchema().slots().values()) {
            if (spec.scope() == SlotScope.REGION) {
                templates.add(spec.name());
            }
        }
        if (templates.isEmpty()) {
            return writes;
        }
        RegionDefinition region = workflow.regionContaining(nodeId)
                .filter(RegionDefinition::hasSlotPrefix)
                .orElse(null);
        if (region == null) {
            return writes;
        }
        Map<String, Object> expanded = new LinkedHashMap<>();
        writes.forEach((name, value) -> expanded.put(
                templates.contains(name) ? region.slotPrefix() + "_" + name : name, value));
        return expanded;
    }

    /**
     * @param cursor   当前游标
     * @param resolved 节点解析结果
     * @return 节点所属循环的当前迭代数，不在显式循环中时返回 0
     */
    private int iterationOf(Cursor cursor, ResolvedPolicy resolved) {
        if (resolved == null || !resolved.inLoop()) {
            return 0;
        }
        return cursor.loopCounters().getOrDefault(resolved.loopId(), 0);
    }

    /**
     * 观测收敛：在循环出口节点执行之后读取槽位值，计算与上一轮迭代的差值。
     *
     * <p>观测点放在出口而不是入口，是因为入口节点执行前拿到的仍是上一轮的产物；
     * 放在出口才能比较「本轮产物」与「上一轮产物」。状态写入游标 {@code state}，
     * 随检查点一起持久化，恢复后仍可继续判定。</p>
     *
     * @param cursor   当前游标
     * @param node     当前节点
     * @param resolved 节点解析结果
     * @param slots    槽位容器
     * @return 更新后的游标
     */
    private Cursor observeConvergence(Cursor cursor, NodeDefinition node, ResolvedPolicy resolved, Slots slots) {
        if (resolved == null || !resolved.inLoop() || !resolved.loop().hasConvergence()) {
            return cursor;
        }
        if (!node.id().equals(resolved.loop().exit())) {
            return cursor;
        }
        com.agentframework.definition.region.LoopConvergence convergence = resolved.loop().convergence();
        Double current = toNumber(slots.get(convergence.slot()));
        if (current == null) {
            return cursor;
        }
        Double previous = toNumber(cursor.state().get(prevKey(resolved.loopId())));
        Cursor updated = cursor;
        if (previous != null) {
            updated = updated.withState(deltaKey(resolved.loopId()), Math.abs(current - previous));
        }
        return updated.withState(prevKey(resolved.loopId()), current);
    }

    /**
     * @param cursor   当前游标
     * @param node     当前节点
     * @param resolved 节点解析结果
     * @return 收敛导致的中断原因，未收敛时返回 null
     */
    private String convergenceBreak(Cursor cursor, NodeDefinition node, ResolvedPolicy resolved) {
        if (resolved == null || !resolved.inLoop() || !resolved.loop().hasConvergence()) {
            return null;
        }
        if (!node.id().equals(resolved.loop().exit())) {
            return null;
        }
        Double delta = toNumber(cursor.state().get(deltaKey(resolved.loopId())));
        if (delta == null) {
            return null;
        }
        double minDelta = resolved.loop().convergence().minDelta();
        if (delta < minDelta) {
            return "converged:" + resolved.loop().convergence().slot() + " delta=" + delta + " < " + minDelta;
        }
        return null;
    }

    /**
     * @param loopId 循环标识
     * @return 上一次观测值在游标状态中的键
     */
    private String prevKey(String loopId) {
        return "loop." + loopId + ".prev";
    }

    /**
     * @param loopId 循环标识
     * @return 相邻差值在游标状态中的键
     */
    private String deltaKey(String loopId) {
        return "loop." + loopId + ".delta";
    }

    /**
     * @param value 原始值
     * @return 数值视图，非数字时返回 null
     */
    private Double toNumber(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof CharSequence text) {
            try {
                return Double.valueOf(text.toString().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 判断一条边是否为回边：目标节点可以沿有向边回到起点。
     *
     * @param workflow 工作流
     * @param edge     待判断的边
     * @return 是回边返回 true
     */
    private boolean isBackEdge(WorkflowDefinition workflow, Edge edge) {
        java.util.Set<String> visited = new java.util.LinkedHashSet<>();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        queue.add(edge.to());
        visited.add(edge.to());
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (current.equals(edge.from())) {
                return true;
            }
            for (Edge outgoing : workflow.outgoing(current)) {
                if (visited.add(outgoing.to())) {
                    queue.add(outgoing.to());
                }
            }
        }
        return false;
    }

    /**
     * @param slots 槽位容器
     * @return 表达式求值变量表
     */
    private Map<String, Object> variables(Slots slots) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("slots", slots.asMap());
        variables.put("slot", slots.asMap());
        return variables;
    }

    /**
     * 取当前节点已解析的策略。
     *
     * @param context 节点上下文
     * @return 解析结果，未启用解析链时返回 null
     */
    private ResolvedPolicy resolvedPolicy(NodeContext context, NodeDefinition node) {
        Object index = context.attributes().get(PolicyAttributes.INDEX);
        if (index instanceof ResolvedPolicyIndex resolvedIndex) {
            return resolvedIndex.node(node.id());
        }
        Object value = context.attributes().get(PolicyAttributes.RESOLVED);
        return value instanceof ResolvedPolicy policy ? policy : null;
    }

    /**
     * 构建或复用当前 Agent + Workflow 的策略索引。
     *
     * @param execution 工作流执行输入
     * @return 策略索引，未配置解析器时返回 null
     */
    private ResolvedPolicyIndex index(WorkflowExecution execution) {
        if (policyResolver == null || execution.agent() == null || execution.workflow() == null) {
            return null;
        }
        Agent agent = execution.agent();
        WorkflowDefinition workflow = execution.workflow();
        String key = ResolvedPolicyIndex.cacheKey(agent.definition(), workflow, policyResolver.catalogVersion());
        return policyIndexCache.computeIfAbsent(key,
                ignored -> ResolvedPolicyIndex.build(policyResolver, agent.definition(), workflow));
    }

    /**
     * 组装节点级拦截器上下文。
     *
     * @param session 会话
     * @param node    节点定义
     * @param context 节点上下文
     * @return 拦截器上下文
     */
    private InterceptorContext interceptorContext(Session session, NodeDefinition node, NodeContext context) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(InterceptorAttributes.NODE_ID, node.id());
        attributes.put(InterceptorAttributes.AGENT_ID, context.agent() == null ? null : context.agent().id());
        attributes.put(InterceptorAttributes.TIMEOUT_POLICY, TimeoutPolicy.of(config.nodeTimeout()));
        return InterceptorContext.of(InterceptorPhase.AROUND_NODE, "node:" + node.id())
                .withOwner(session.id(), node.id())
                .withAttributes(attributes);
    }

    /**
     * 组装守卫上下文。
     *
     * @param phase   挂载点
     * @param session 会话
     * @param node    节点定义
     * @param context 节点上下文
     * @param payload 被检查载荷
     * @return 守卫上下文
     */
    private GuardContext guardContext(GuardPhase phase, Session session, NodeDefinition node, NodeContext context,
            Object payload) {
        ResolvedPolicy resolved = resolvedPolicy(context, node);
        String loopKey = resolved != null && resolved.inLoop() ? resolved.loopId() : node.id();
        GuardContext guardContext = GuardContext.of(phase, payload)
                .withSession(session.id(), session.tenantId())
                .withOwner(context.agent() == null ? null : context.agent().id(), node.id())
                .withSlots(context.slots())
                .withAttributes(Map.of("nodeType", node.type().name()))
                .withLoop(loopKey, session.cursor().loopCounters());
        if (resolved != null && resolved.inRegion() && quotaEnforcer != null) {
            guardContext = guardContext.withAttribute(GuardContext.REGION_TOKENS,
                    quotaEnforcer.usage(session.id(), resolved.regionId()).tokens());
        }
        return guardContext;
    }

    /**
     * 构造循环中断结果：节点不再执行，标记中断原因供运行循环跳过回边。
     *
     * @param node   节点定义
     * @param reason 中断原因
     * @return 节点结果
     */
    private NodeResult breakResult(NodeDefinition node, String reason) {
        return NodeResult.completed(node.id(), "").withMetadata(LOOP_BREAK_METADATA, reason);
    }

    /**
     * 开启节点 span。
     *
     * @param context 节点上下文
     * @param node    节点定义
     * @return span，未启用追踪时为 null
     */
    private Span startSpan(NodeContext context, NodeDefinition node, ResolvedPolicy resolved) {
        if (tracer == null || context.trace() == null || !context.trace().active()) {
            return null;
        }
        return tracer.startSpan(context.trace(), context.parentSpan(), "node:" + node.id(), SpanKind.NODE,
                spanAttributes(node, resolved));
    }

    /**
     * @param node     节点定义
     * @param resolved 解析结果
     * @return 指标标签，归属 Region 时附带 region / paradigm
     */
    private Map<String, Object> metricTags(NodeDefinition node, ResolvedPolicy resolved) {
        Map<String, Object> tags = new LinkedHashMap<>();
        tags.put("node", node.id());
        tags.put("type", node.type().name());
        if (resolved != null && resolved.inRegion()) {
            tags.put("region", resolved.regionId());
            tags.put("paradigm", resolved.paradigm());
        }
        return Map.copyOf(tags);
    }

    /**
     * @param node     节点定义
     * @param resolved 解析结果
     * @return Span 属性，归属 Region 时附带 region / paradigm
     */
    private Map<String, Object> spanAttributes(NodeDefinition node, ResolvedPolicy resolved) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("nodeType", node.type().name());
        if (resolved != null && resolved.inRegion()) {
            attributes.put("region", resolved.regionId());
            attributes.put("paradigm", resolved.paradigm());
        }
        return Map.copyOf(attributes);
    }

    /**
     * 校验配额。
     *
     * @param agent     Agent 实例
     * @param sessionId 会话 id
     */
    private void consumeQuota(Agent agent, String sessionId, ResolvedPolicy resolved) {
        if (quotaEnforcer == null) {
            return;
        }
        QuotaPolicy policy = agent.policies().quota();
        quotaEnforcer.consumeNode(sessionId, Quota.from(policy));
        if (resolved != null && resolved.inRegion()) {
            quotaEnforcer.consumeNode(sessionId, resolved.regionId(), Quota.from(resolved.regionQuota()));
        }
    }

    /**
     * 记录本次节点消耗的 token 并校验上限。
     *
     * @param agent      Agent 实例
     * @param sessionId  会话 id
     * @param nodeUsage  本次节点的 token 用量
     */
    private void consumeTokens(Agent agent, String sessionId, Usage nodeUsage, ResolvedPolicy resolved) {
        if (quotaEnforcer == null || nodeUsage == null || nodeUsage.total() <= 0) {
            return;
        }
        QuotaPolicy policy = agent.policies().quota();
        quotaEnforcer.consumeTokens(sessionId, nodeUsage.total(), Quota.from(policy));
        if (resolved != null && resolved.inRegion()) {
            quotaEnforcer.consumeTokens(sessionId, resolved.regionId(), nodeUsage.total(),
                    Quota.from(resolved.regionQuota()));
        }
    }

    /**
     * 校验会话墙钟耗时。
     *
     * @param agent      Agent 实例
     * @param sessionId  会话 id
     * @param startNanos 本次运行开始时间
     */
    private void checkWallTime(Agent agent, String sessionId, long startNanos, ResolvedPolicy resolved) {
        if (quotaEnforcer == null) {
            return;
        }
        QuotaPolicy policy = agent.policies().quota();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
        quotaEnforcer.checkWallTime(sessionId, elapsed, Quota.from(policy));
        if (resolved != null && resolved.inRegion()) {
            quotaEnforcer.checkWallTime(sessionId, resolved.regionId(), elapsed,
                    Quota.from(resolved.regionQuota()));
        }
    }

    /**
     * @param result 节点结果
     * @return 该节点消耗的 token
     */
    private Usage usageOf(NodeResult result) {
        Object usage = result.metadata().get("usage");
        return usage instanceof Usage tokenUsage ? tokenUsage : Usage.zero();
    }

    /**
     * 本步调用的模型名。
     *
     * <p>模型名一直躺在 LLM 执行器写回的 metadata 里（见 LlmNodeExecutor），
     * 只是此前没进轨迹；轨迹上要它，是因为「同一条链路换过模型」这种事
     * 事后只能从模型名看出来。</p>
     *
     * @param result 节点结果
     * @return 模型名；非 LLM 步（或执行器没写）为 null
     */
    private String modelOf(NodeResult result) {
        Object model = result.metadata().get("model");
        return model instanceof String name ? name : null;
    }

    /**
     * 本步的工具调用记录。
     *
     * <p>由工具执行器（如诊断链的 ActExecutor）写进 metadata，这里只做透传 ——
     * 内核不解释工具语义，只负责让它进得了轨迹。</p>
     *
     * @param result 节点结果
     * @return 调用记录；非工具步（或执行器没写）为 null
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> toolCallOf(NodeResult result) {
        Object toolCall = result.metadata().get("toolCall");
        return toolCall instanceof Map<?, ?> document ? (Map<String, Object>) document : null;
    }

    /**
     * 保存检查点。
     *
     * @param session 会话
     * @param cursor  游标
     * @param slots   槽位
     */
    private void save(Session session, Cursor cursor, Slots slots) {
        updateCursor(session, cursor);
        if (checkpoint != null) {
            checkpoint.save(session, cursor, slots);
        }
    }

    /**
     * 更新会话游标；不可变会话视图会被安全忽略。
     *
     * @param session 会话
     * @param cursor  新游标
     */
    private void updateCursor(Session session, Cursor cursor) {
        if (session instanceof MutableSession mutable) {
            mutable.cursor(cursor);
        }
    }

    /**
     * 发布节点事件。
     *
     * @param session 会话
     * @param type    事件类型
     * @param nodeId  节点 id
     * @param detail  结果描述
     */
    private void publish(Session session, String type, String nodeId, String detail) {
        if (events == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeId", nodeId);
        if (detail != null) {
            payload.put("detail", detail);
        }
        events.publish(Event.of(type, session.id(), payload).withTrace(session.traceId()));
    }

    /**
     * 发布结构化节点事件。
     *
     * @param session 会话
     * @param type    事件类型
     * @param payload 事件负载
     */
    private void publish(Session session, String type, Map<String, Object> payload) {
        if (events == null) {
            return;
        }
        events.publish(Event.of(type, session.id(), payload).withTrace(session.traceId()));
    }
}
