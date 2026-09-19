package com.agentframework.engine.toolexecutor;

import com.agentframework.crosscutting.filter.FilterContext;
import com.agentframework.crosscutting.filter.FilterPhase;
import com.agentframework.crosscutting.guard.GuardContext;
import com.agentframework.crosscutting.guard.GuardDecision;
import com.agentframework.crosscutting.guard.GuardPhase;
import com.agentframework.crosscutting.guard.Guards;
import com.agentframework.crosscutting.interceptor.InterceptorAttributes;
import com.agentframework.crosscutting.interceptor.InterceptorContext;
import com.agentframework.crosscutting.interceptor.InterceptorPhase;
import com.agentframework.crosscutting.interceptor.Invocation;
import com.agentframework.crosscutting.metrics.Metrics;
import com.agentframework.crosscutting.trace.SpanKind;
import com.agentframework.definition.policy.QuotaPolicy;
import com.agentframework.definition.policy.ToolPolicy;
import com.agentframework.definition.tool.ToolDefinition;
import com.agentframework.definition.tool.ToolSchema;
import com.agentframework.engine.middleware.MiddlewarePipeline;
import com.agentframework.engine.policy.PolicyAttributes;
import com.agentframework.engine.policy.PolicyGuards;
import com.agentframework.engine.policy.ResolvedPolicy;
import com.agentframework.extension.permission.Quota;
import com.agentframework.extension.permission.QuotaEnforcer;
import com.agentframework.runtime.event.Event;
import com.agentframework.runtime.event.EventBus;
import com.agentframework.runtime.event.Topics;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 默认工具执行器。
 *
 * <p>固定链路：参数校验 → 守卫（{@code BEFORE_TOOL}）→ 参数过滤 → 拦截链（缓存 / 超时 / 重试 / 熔断 /
 * 追踪）→ 工具调用 → 结果过滤 → 守卫（{@code AFTER_TOOL}）→ 事件与指标。失败以
 * {@link ToolResult} 返回，不向调用方抛异常，避免单个工具拖垮整条工作流。</p>
 */
public final class DefaultToolExecutor implements ToolExecutor {

    /** 工具策略在上下文中的属性名，由工具节点写入。 */
    public static final String TOOL_POLICY_ATTRIBUTE = "agent.toolPolicy";

    /** 配额策略在上下文中的属性名，由工具节点写入。 */
    public static final String QUOTA_POLICY_ATTRIBUTE = "agent.quotaPolicy";

    private final ToolRegistry registry;
    private final MiddlewarePipeline middleware;
    private final Metrics metrics;
    private final EventBus events;
    private final QuotaEnforcer quotaEnforcer;

    /**
     * @param registry   工具注册表
     * @param middleware 中间件管道，可为 null
     * @param metrics    指标采集器，可为 null
     * @param events     事件总线，可为 null
     */
    public DefaultToolExecutor(ToolRegistry registry, MiddlewarePipeline middleware, Metrics metrics,
            EventBus events) {
        this(registry, middleware, metrics, events, null);
    }

    /**
     * @param registry      工具注册表
     * @param middleware    中间件管道，可为 null
     * @param metrics       指标采集器，可为 null
     * @param events        事件总线，可为 null
     * @param quotaEnforcer 配额执行器，可为 null
     */
    public DefaultToolExecutor(ToolRegistry registry, MiddlewarePipeline middleware, Metrics metrics,
            EventBus events, QuotaEnforcer quotaEnforcer) {
        this.registry = registry;
        this.middleware = middleware;
        this.metrics = metrics;
        this.events = events;
        this.quotaEnforcer = quotaEnforcer;
    }

    @Override
    public ToolResult execute(ToolInvocation invocation, ToolContext context) {
        // 从注册表中获取工具  Tool 是可执行的实现体。它一定有 invoke 方法，真正干活的是它。
        Tool tool = registry.resolve(invocation.toolId(), invocation.version())
                .orElseThrow(() -> new ToolNotFoundException(invocation.toolId()));
        // 获取工具定义  ToolDefinition 是声明式元数据。它通常包含：schema：参数契约 cachePolicy：缓存策略 timeout：超时策略 retry：重试策略 可能还有权限、标签、版本等
        ToolDefinition definition = registry.definition(invocation.toolId()).orElse(null);

        // 获取工具模式
        ToolSchema schema = definition != null ? definition.schema() : tool.schema();

        // 应用默认参数并验证参数
        Map<String, Object> arguments = schema.applyDefaults(invocation.arguments());
        List<String> problems = schema.validate(arguments);
        if (!problems.isEmpty()) {
            return ToolResult.failed("参数校验失败：" + String.join("；", problems));
        }

        String sessionId = context.sessionId();
        String nodeId = context.nodeId();
        Object policyAttribute = context.attributes().get(TOOL_POLICY_ATTRIBUTE);
        if (policyAttribute instanceof ToolPolicy toolPolicy) {
            GuardDecision decision = new Guards.ToolPermission(toolPolicy)
                    .check(toolGuardContext(invocation, context));
            if (decision instanceof GuardDecision.Deny deny) {
                return ToolResult.failed("工具策略拒绝调用：" + deny.reason());
            }
            if (decision instanceof GuardDecision.AskApproval ask) {
                return ToolResult.failed("需要人工审批：" + ask.reason());
            }
        }
        if (middleware != null) {
            ResolvedPolicy resolved = resolvedPolicy(context);
            GuardContext beforeTool = toolGuardContext(invocation, context);
            if (resolved == null) {
                middleware.requireGuard(GuardPhase.BEFORE_TOOL, beforeTool);
            } else {
                PolicyGuards.require(resolved, GuardPhase.BEFORE_TOOL, beforeTool, events);
            }
            FilterContext filterContext = toolFilterContext(FilterPhase.BEFORE_TOOL_CALL, sessionId, nodeId,
                    invocation);
            arguments = resolved == null
                    ? middleware.filter(FilterPhase.BEFORE_TOOL_CALL, arguments, filterContext)
                    : middleware.filter(FilterPhase.BEFORE_TOOL_CALL, arguments, filterContext, resolved.filters());
        }
        publish(Topics.TOOL_INVOKED, sessionId, context.traceId(), invocation.toolId(), null);
        consumeToolCall(context, sessionId);

        long start = System.nanoTime();
        ToolInput input = new ToolInput(arguments, sessionId, nodeId);
        Invocation<ToolResult> terminal = () -> tool.invoke(input, context);
        ToolResult result;
        try {
            ResolvedPolicy resolved = resolvedPolicy(context);
            result = middleware == null
                    ? terminal.proceed()
                    : resolved == null
                            ? middleware.intercept(InterceptorPhase.AROUND_TOOL,
                                    interceptorContext(definition, invocation, arguments, context), terminal)
                            : middleware.intercept(InterceptorPhase.AROUND_TOOL,
                                    interceptorContext(definition, invocation, arguments, context),
                                    resolved.interceptors(), terminal);
        } catch (Exception e) {
            result = ToolResult.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (result == null) {
            result = ToolResult.failed("工具未返回结果：" + invocation.toolId());
        }

        if (middleware != null && result.success()) {
            ResolvedPolicy resolved = resolvedPolicy(context);
            FilterContext filterContext = toolFilterContext(FilterPhase.AFTER_TOOL_RESULT, sessionId, nodeId,
                    invocation);
            String output = resolved == null
                    ? middleware.filter(FilterPhase.AFTER_TOOL_RESULT, result.output(), filterContext)
                    : middleware.filter(FilterPhase.AFTER_TOOL_RESULT, result.output(), filterContext,
                            resolved.filters());
            result = result.withOutput(output);
            GuardContext afterTool = toolGuardContext(invocation, context)
                    .withPhase(GuardPhase.AFTER_TOOL)
                    .withPayload(result);
            if (resolved == null) {
                middleware.requireGuard(GuardPhase.AFTER_TOOL, afterTool);
            } else {
                PolicyGuards.require(resolved, GuardPhase.AFTER_TOOL, afterTool, events);
            }
        }
        if (result.duration().isZero()) {
            result = result.withDuration(Duration.ofNanos(System.nanoTime() - start));
        }

        if (metrics != null) {
            Map<String, Object> tags = Map.of("tool", invocation.toolId());
            metrics.counter(result.success() ? "tool.calls" : "tool.failures", 1L, tags);
            metrics.histogram("tool.duration_ms", result.duration().toMillis(), tags);
        }
        publish(Topics.TOOL_COMPLETED, sessionId, context.traceId(), invocation.toolId(),
                result.success() ? "ok" : result.error());
        return result;
    }

    /**
     * 组装工具调用的拦截器上下文，把工具定义上的策略翻译成标准属性。
     *
     * @param definition 工具定义，可为 null
     * @param invocation 调用请求
     * @param arguments  实际参数
     * @param context    执行上下文
     * @return 拦截器上下文
     */
    private InterceptorContext interceptorContext(ToolDefinition definition, ToolInvocation invocation,
            Map<String, Object> arguments, ToolContext context) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(InterceptorAttributes.TOOL_ID, invocation.toolId());
        attributes.put(InterceptorAttributes.SPAN_KIND, SpanKind.TOOL);
        attributes.put(InterceptorAttributes.SPAN_NAME, "tool:" + invocation.toolId());
        attributes.put(InterceptorAttributes.CACHE_INPUT, arguments);
        attributes.put(InterceptorAttributes.CACHE_NAMESPACE, context.sessionId());
        attributes.put(InterceptorAttributes.CACHE_OPERATION, "tool:" + invocation.toolId());
        attributes.put(InterceptorAttributes.CIRCUIT_KEY, "tool:" + invocation.toolId());
        Object trace = context.attributes().get(InterceptorAttributes.TRACE);
        if (trace != null) {
            attributes.put(InterceptorAttributes.TRACE, trace);
        }
        Object traceParent = context.attributes().get(InterceptorAttributes.TRACE_PARENT);
        if (traceParent != null) {
            attributes.put(InterceptorAttributes.TRACE_PARENT, traceParent);
        }
        if (definition != null) {
            attributes.put(InterceptorAttributes.CACHE_POLICY, definition.cachePolicy());
            attributes.put(InterceptorAttributes.TIMEOUT_POLICY, definition.timeout());
            attributes.put(InterceptorAttributes.RETRY_POLICY, definition.retry());
        }
        return InterceptorContext.of(InterceptorPhase.AROUND_TOOL, "tool:" + invocation.toolId())
                .withOwner(context.sessionId(), context.nodeId())
                .withAttributes(attributes);
    }

    /**
     * 构建工具调用的守卫上下文（before_tool 语义）。
     *
     * @param invocation 工具调用
     * @param context    工具上下文
     * @return 守卫上下文
     */
    private GuardContext toolGuardContext(ToolInvocation invocation, ToolContext context) {
        return GuardContext.of(GuardPhase.BEFORE_TOOL, invocation)
                .withSession(context.sessionId(), null)
                .withOwner(null, context.nodeId())
                .withSlots(context.slots())
                .withAttributes(Map.of("toolId", invocation.toolId()));
    }

    /**
     * 构建工具调用的过滤上下文。
     *
     * @param phase      挂载点
     * @param sessionId  会话 id
     * @param nodeId     节点 id
     * @param invocation 工具调用
     * @return 过滤上下文
     */
    private FilterContext toolFilterContext(FilterPhase phase, String sessionId, String nodeId,
            ToolInvocation invocation) {
        return FilterContext.of(phase)
                .withOwner(sessionId, nodeId)
                .withAttributes(Map.of("toolId", invocation.toolId()));
    }

    /**
     * 取当前节点已解析的策略。
     *
     * @param context 工具上下文
     * @return 解析结果，未启用解析链时返回 null
     */
    private ResolvedPolicy resolvedPolicy(ToolContext context) {
        Object value = context.attributes().get(PolicyAttributes.RESOLVED);
        return value instanceof ResolvedPolicy policy ? policy : null;
    }

    /**
     * 记录一次工具调用并校验配额。
     *
     * @param context   工具上下文
     * @param sessionId 会话 id
     */
    private void consumeToolCall(ToolContext context, String sessionId) {
        if (quotaEnforcer == null) {
            return;
        }
        Object raw = context.attributes().get(QUOTA_POLICY_ATTRIBUTE);
        QuotaPolicy policy = raw instanceof QuotaPolicy candidate ? candidate : null;
        quotaEnforcer.consumeToolCall(sessionId, Quota.from(policy));
        ResolvedPolicy resolved = resolvedPolicy(context);
        if (resolved != null && resolved.inRegion()) {
            quotaEnforcer.consumeToolCall(sessionId, resolved.regionId(), Quota.from(resolved.regionQuota()));
        }
    }

    /**
     * 发布工具相关事件。
     *
     * @param type      事件类型
     * @param sessionId 会话 id
     * @param traceId   链路 id
     * @param toolId    工具 id
     * @param detail    结果描述，可为 null
     */
    private void publish(String type, String sessionId, String traceId, String toolId, String detail) {
        if (events == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolId", toolId);
        if (detail != null) {
            payload.put("detail", detail);
        }
        events.publish(Event.of(type, sessionId, payload).withTrace(traceId));
    }
}
