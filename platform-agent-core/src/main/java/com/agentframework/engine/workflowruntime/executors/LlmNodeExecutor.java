package com.agentframework.engine.workflowruntime.executors;

import com.agentframework.crosscutting.interceptor.InterceptorAttributes;
import com.agentframework.crosscutting.interceptor.InterceptorContext;
import com.agentframework.crosscutting.interceptor.InterceptorPhase;
import com.agentframework.crosscutting.interceptor.Invocation;
import com.agentframework.crosscutting.filter.FilterContext;
import com.agentframework.crosscutting.filter.FilterPhase;
import com.agentframework.crosscutting.guard.GuardContext;
import com.agentframework.crosscutting.guard.GuardPhase;
import com.agentframework.crosscutting.trace.SpanKind;
import com.agentframework.definition.agent.ModelConfig;
import com.agentframework.definition.node.LlmNodeDefinition;
import com.agentframework.definition.workflow.WorkflowDefinition;
import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.node.NodeType;
import com.agentframework.definition.policy.TimeoutPolicy;
import com.agentframework.definition.tool.ToolSchema;
import com.agentframework.engine.core.NodeContext;
import com.agentframework.engine.core.NodeResult;
import com.agentframework.engine.middleware.MiddlewarePipeline;
import com.agentframework.engine.policy.PolicyAttributes;
import com.agentframework.engine.policy.PolicyGuards;
import com.agentframework.engine.policy.ResolvedPolicy;
import com.agentframework.engine.promptmanager.PromptContext;
import com.agentframework.engine.promptmanager.PromptManager;
import com.agentframework.engine.promptmanager.PromptRequest;
import com.agentframework.engine.toolexecutor.ToolRegistry;
import com.agentframework.engine.workflowruntime.NodeExecutor;
import com.agentframework.infra.modelgateway.ChatMessage;
import com.agentframework.infra.modelgateway.ModelCallContext;
import com.agentframework.infra.modelgateway.ModelGateway;
import com.agentframework.infra.modelgateway.ModelRequest;
import com.agentframework.infra.modelgateway.ModelResponse;
import com.agentframework.runtime.session.Message;
import com.agentframework.runtime.session.Session;
import com.agentframework.runtime.event.Event;
import com.agentframework.runtime.event.EventBus;
import com.agentframework.runtime.event.Topics;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 节点执行器：构建 Prompt → 调用模型 → 写入槽位。
 *
 * <p>模型调用被包在 {@code AROUND_LLM} 拦截链里，因此缓存、超时、重试、追踪对 LLM 与工具一致生效。</p>
 *
 * <p><b>流式</b>：节点 {@code meta} 声明 {@code stream=true} 时走 {@link ModelGateway#stream}，
 * token 片段以 {@link com.agentframework.runtime.event.Topics#MODEL_TOKEN} 事件外发（payload 含
 * {@code nodeId} 与 {@code text}）；拦截链仍包住「整个流到完成」，缓存/超时/重试/追踪语义不变
 * （缓存命中则无 token 帧；重试若在已发 token 后重跑会重复外发，由消费端按会话整帧替换自愈）。
 * 流式结果仍聚合为完整内容写槽位，同步契约与 {@code complete} 路径一致。</p>
 */
public final class LlmNodeExecutor implements NodeExecutor {

    private final PromptManager promptManager;
    private final ModelGateway modelGateway;
    private final MiddlewarePipeline middleware;
    private final ToolRegistry toolRegistry;
    private final EventBus events;

    /**
     * @param promptManager Prompt 管理器
     * @param modelGateway  模型网关
     * @param middleware    中间件管道，可为 null
     * @param toolRegistry  工具注册表，用于声明可调用工具，可为 null
     */
    public LlmNodeExecutor(PromptManager promptManager, ModelGateway modelGateway, MiddlewarePipeline middleware,
            ToolRegistry toolRegistry) {
        this(promptManager, modelGateway, middleware, toolRegistry, null);
    }

    /**
     * @param promptManager Prompt 管理器
     * @param modelGateway  模型网关
     * @param middleware    中间件管道，可为 null
     * @param toolRegistry  工具注册表，用于声明可调用工具，可为 null
     * @param events        事件总线，用于守卫失败开放审计，可为 null
     */
    public LlmNodeExecutor(PromptManager promptManager, ModelGateway modelGateway, MiddlewarePipeline middleware,
            ToolRegistry toolRegistry, EventBus events) {
        this.promptManager = promptManager;
        this.modelGateway = modelGateway;
        this.middleware = middleware;
        this.toolRegistry = toolRegistry;
        this.events = events;
    }

    @Override
    public NodeType type() {
        return NodeType.LLM;
    }

    @Override
    public NodeResult execute(NodeDefinition node, NodeContext context) {
        LlmNodeDefinition llm = (LlmNodeDefinition) node;
        Session session = context.session();
        ResolvedPolicy policy = resolvedPolicy(context);
        PromptContext promptContext = PromptContext.of(context.variables())
                .withOwner(session.id(), node.id())
                .withMessages(session.messages())
                .withAttributes(Map.of("agentId", context.agent() == null ? "" : context.agent().id()));
        String rendered = promptManager.build(
                PromptRequest.of(llm.promptRef(), llm.promptVersion(), promptContext).withPolicy(policy));
        if (rendered == null || rendered.isBlank()) {
            return NodeResult.failed(node.id(), "Prompt 渲染结果为空：" + llm.promptRef());
        }
        String prompt = rendered;
        if (middleware != null && policy != null) {
            prompt = requireBeforeLlm(policy, llm, prompt, context, session);
        }
        ModelConfig model = context.agent() == null
                ? ModelConfig.defaults()
                : context.agent().modelConfig().overlay(llm.modelOverride());
        ModelRequest request = ModelRequest.of(model.provider(), model.model(), buildMessages(prompt, context))
                .withTemperature(model.temperatureSet() ? model.temperature() : null)
                .withMaxTokens(model.maxTokens() > 0 ? model.maxTokens() : null)
                .withTools(availableTools(context))
                .withParameters(model.parameters());
        ModelCallContext callContext = ModelCallContext.of(session.id(), node.id())
                .withTraceId(session.traceId())
                .withTimeout(model.timeout())
                .withAttributes(Map.of("promptRef", llm.promptRef()));

        ModelResponse response;
        try {
            boolean streaming = llm.meta() != null && llm.meta().booleanAttribute("stream", false);
            Invocation<ModelResponse> terminal = streaming
                    ? () -> modelGateway.stream(request, callContext,
                            piece -> publishModelToken(session.id(), node.id(), piece))
                    : () -> modelGateway.complete(request, callContext);
            response = middleware == null
                    ? terminal.proceed()
                    : policy == null
                            ? middleware.intercept(InterceptorPhase.AROUND_LLM,
                                    interceptorContext(node, context, model, prompt, request), terminal)
                            : middleware.intercept(InterceptorPhase.AROUND_LLM,
                                    interceptorContext(node, context, model, prompt, request),
                                    policy.interceptors(), terminal);
        } catch (Exception e) {
            return NodeResult.failed(node.id(), "模型调用失败：" + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
        if (response == null) {
            return NodeResult.failed(node.id(), "模型未返回结果");
        }
        String content = response.content();
        if (middleware != null && policy != null) {
            content = applyAfterLlm(policy, content, context, session, response, node.id());
        }
        Map<String, Object> writes = new LinkedHashMap<>();
        writes.put(llm.outputSlot(), content);
        if (response.hasToolCalls()) {
            writes.put(llm.outputSlot() + WorkflowDefinition.TOOL_CALLS_SLOT_SUFFIX, response.toolCalls());
        }
        return NodeResult.completed(node.id(), content, writes)
                .withMessage(Message.assistant(content))
                .withMetadata("usage", response.usage())
                .withMetadata("model", response.model())
                .withMetadata("finishReason", response.finishReason());
    }

    /**
     * 组装模型消息：system 提示词 + 会话历史 + 本次输入。
     *
     * @param prompt  system 提示词
     * @param context 节点上下文
     * @return 模型消息列表
     */
    private List<ChatMessage> buildMessages(String prompt, NodeContext context) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(prompt));
        List<Message> history = context.session().messages();
        for (Message message : history) {
            messages.add(ChatMessage.from(message));
        }
        String input = context.input() == null ? "" : context.input().text();
        boolean alreadyPresent = !history.isEmpty()
                && history.get(history.size() - 1).content().equals(input);
        if (!input.isBlank() && !alreadyPresent) {
            messages.add(ChatMessage.user(input));
        }
        return messages;
    }

    /**
     * 执行 {@code BEFORE_LLM} 守卫与过滤，返回可送往模型的系统提示词。
     *
     * @param policy  解析结果
     * @param llm     LLM 节点定义
     * @param prompt  渲染后的提示词
     * @param context 节点上下文
     * @param session 会话
     * @return 守卫与过滤后的提示词
     */
    private String requireBeforeLlm(ResolvedPolicy policy, LlmNodeDefinition llm, String prompt,
            NodeContext context, Session session) {
        GuardContext guardContext = GuardContext.of(GuardPhase.BEFORE_LLM, prompt)
                .withSession(session.id(), session.tenantId())
                .withOwner(context.agent() == null ? null : context.agent().id(), llm.id())
                .withSlots(context.slots())
                .withAttributes(Map.of("promptRef", llm.promptRef()));
        Object allowed = PolicyGuards.require(policy, GuardPhase.BEFORE_LLM, guardContext, events);
        String current = allowed == null ? prompt : String.valueOf(allowed);
        FilterContext filterContext = FilterContext.of(FilterPhase.BEFORE_LLM)
                .withOwner(session.id(), llm.id())
                .withAttributes(Map.of("promptRef", llm.promptRef()));
        return middleware.filter(FilterPhase.BEFORE_LLM, current, filterContext, policy.filters());
    }

    /**
     * 执行 {@code AFTER_LLM} 过滤与守卫，返回最终模型输出。
     *
     * @param policy   解析结果
     * @param content  模型输出
     * @param context  节点上下文
     * @param session  会话
     * @param response 模型响应
     * @param nodeId   节点 id
     * @return 过滤与守卫后的输出
     */
    private String applyAfterLlm(ResolvedPolicy policy, String content, NodeContext context, Session session,
            ModelResponse response, String nodeId) {
        FilterContext filterContext = FilterContext.of(FilterPhase.AFTER_LLM)
                .withOwner(session.id(), nodeId)
                .withAttributes(Map.of("model", String.valueOf(response.model())));
        String current = middleware.filter(FilterPhase.AFTER_LLM, content, filterContext, policy.filters());
        GuardContext guardContext = GuardContext.of(GuardPhase.AFTER_LLM, current)
                .withSession(session.id(), session.tenantId())
                .withOwner(context.agent() == null ? null : context.agent().id(), nodeId)
                .withSlots(context.slots())
                .withAttributes(Map.of("model", String.valueOf(response.model())));
        Object allowed = PolicyGuards.require(policy, GuardPhase.AFTER_LLM, guardContext, events);
        return allowed == null ? current : String.valueOf(allowed);
    }

    /**
     * 取当前节点已解析的策略。
     *
     * @param context 节点上下文
     * @return 解析结果，未启用解析链时返回 null
     */
    private ResolvedPolicy resolvedPolicy(NodeContext context) {
        Object value = context.attributes().get(PolicyAttributes.RESOLVED);
        return value instanceof ResolvedPolicy policy ? policy : null;
    }

    /**
     * 发布流式 token 事件。
     *
     * <p>事件总线为 null（手工装配）或发布异常时静默忽略——token 帧是尽力而为的增量推送，
     * 最终内容仍会完整写入槽位与完成结果，不因订阅方故障反压模型流。</p>
     *
     * @param sessionId 会话 id
     * @param nodeId    节点 id
     * @param piece     文本片段
     */
    private void publishModelToken(String sessionId, String nodeId, String piece) {
        if (events == null || piece == null || piece.isEmpty()) {
            return;
        }
        try {
            events.publish(Event.of(Topics.MODEL_TOKEN, sessionId, Map.of("nodeId", nodeId, "text", piece)));
        } catch (RuntimeException ignored) {
            // 订阅方异常不中断模型流
        }
    }

    /**
     * 收集当前 Agent 策略允许的工具契约。
     *
     * @param context 节点上下文
     * @return 工具契约列表
     */
    private List<ToolSchema> availableTools(NodeContext context) {
        if (toolRegistry == null || context.agent() == null) {
            return List.of();
        }
        return toolRegistry.list().stream()
                .filter(tool -> context.agent().policies().tool().allows(tool.id()))
                .map(tool -> tool.schema() == null ? null : tool.schema())
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * 组装模型调用的拦截器上下文。
     *
     * @param node    节点定义
     * @param context 节点上下文
     * @param model   有效模型配置
     * @param prompt  提示词文本
     * @param request 模型请求
     * @return 拦截器上下文
     */
    private InterceptorContext interceptorContext(NodeDefinition node, NodeContext context, ModelConfig model,
            String prompt, ModelRequest request) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(InterceptorAttributes.SPAN_KIND, SpanKind.LLM);
        attributes.put(InterceptorAttributes.SPAN_NAME, "llm:" + node.id());
        attributes.put(InterceptorAttributes.MODEL, model.model());
        attributes.put(InterceptorAttributes.CACHE_INPUT, model.model() + "|" + prompt);
        attributes.put(InterceptorAttributes.CACHE_NAMESPACE, context.session().id());
        attributes.put(InterceptorAttributes.CACHE_OPERATION, "llm:" + node.id());
        attributes.put(InterceptorAttributes.CIRCUIT_KEY, "llm:" + model.provider());
        attributes.put(InterceptorAttributes.TIMEOUT_POLICY, TimeoutPolicy.of(model.timeout()));
        attributes.put(InterceptorAttributes.RETRY_POLICY, model.retry());
        attributes.put(InterceptorAttributes.TRACE, context.trace());
        attributes.put(InterceptorAttributes.TRACE_PARENT, context.parentSpan());
        if (context.workflow() != null) {
            attributes.put(InterceptorAttributes.CACHE_POLICY, context.workflow().cachePolicy());
        }
        return InterceptorContext.of(InterceptorPhase.AROUND_LLM, "llm:" + node.id())
                .withOwner(context.session().id(), node.id())
                .withAttributes(attributes);
    }
}
