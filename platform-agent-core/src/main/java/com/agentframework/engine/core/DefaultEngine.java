package com.agentframework.engine.core;

import com.agentframework.crosscutting.filter.FilterContext;
import com.agentframework.crosscutting.filter.FilterPhase;
import com.agentframework.crosscutting.guard.GuardContext;
import com.agentframework.crosscutting.guard.GuardDeniedException;
import com.agentframework.crosscutting.guard.GuardPhase;
import com.agentframework.crosscutting.guard.LoopBreakException;
import com.agentframework.crosscutting.interceptor.InterceptorAttributes;
import com.agentframework.crosscutting.interceptor.InterceptorContext;
import com.agentframework.crosscutting.interceptor.InterceptorPhase;
import com.agentframework.crosscutting.trace.SpanKind;
import com.agentframework.crosscutting.trace.TraceContext;
import com.agentframework.definition.agent.AgentDefinition;
import com.agentframework.engine.agentmanager.Agent;
import com.agentframework.engine.agentmanager.AgentManager;
import com.agentframework.engine.agentmanager.DefinitionSource;
import com.agentframework.engine.contextmanager.ContextManager;
import com.agentframework.engine.persistence.PersistenceManager;
import com.agentframework.engine.persistence.RollbackResult;
import com.agentframework.engine.pluginruntime.PluginRuntime;
import com.agentframework.engine.policy.*;
import com.agentframework.engine.promptmanager.PromptManager;
import com.agentframework.engine.scheduling.RecoveryManager;
import com.agentframework.engine.toolexecutor.ToolRegistry;
import com.agentframework.extension.registry.ExtensionRegistry;
import com.agentframework.runtime.event.Event;
import com.agentframework.runtime.event.EventBus;
import com.agentframework.runtime.event.Topics;
import com.agentframework.runtime.persistence.CheckpointEntry;
import com.agentframework.runtime.session.*;
import com.agentframework.runtime.slot.Slots;
import com.agentframework.runtime.workspace.Workspace;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认引擎实现：把一次调用串成完整执行链。
 *
 * <p>执行顺序：守卫（before_agent）→ 输入过滤 → 拦截链（around_agent）→ 工作流运行时 →
 * 输出过滤 → 守卫（before_output）→ 状态落库 → 事件与追踪导出。</p>
 */
public final class DefaultEngine implements Engine {

    private final EngineServices services;
    private final Map<String, Agent> sessionAgents = new ConcurrentHashMap<>();

    /** @param services 装配好的引擎组件集合 */
    public DefaultEngine(EngineServices services) {
        this.services = services;
    }

    @Override
    public Agent createAgent(AgentDefinition definition) {
        return services.agents().create(definition);
    }

    @Override
    public Agent loadAgent(String agentId, String version) {
        return services.agents().load(agentId, version);
    }

    @Override
    public Session startSession(Agent agent, StartOptions options) {
        Session session = services.contexts().createSession(agent, options);
        sessionAgents.put(session.id(), agent);
        publish(Topics.SESSION_STARTED, session, Map.of("agentId", agent.id(), "agentVersion", agent.version()));
        services.contexts().persist(session);
        return session;
    }

    @Override
    public RunResult run(Session session, Input input) {
        return execute(session, input, false);
    }

    @Override
    public RunResult resume(Session session, Input input) {
        return execute(session, input, true);
    }

    @Override
    public void cancel(Session session) {
        MutableSession mutable = requireMutable(session);
        mutable.state(SessionState.CANCELLED);
        services.contexts().persist(mutable);
        publish(Topics.SESSION_CANCELLED, mutable, Map.of());
    }

    @Override
    public RunResult run(String agentId, Input input) {
        Agent agent = services.agents().load(agentId, AgentDefinition.LATEST);
        Session session = startSession(agent, StartOptions.defaults());
        return run(session, input);
    }

    /**
     * 执行一次完整运行。
     *
     * @param session 会话
     * @param input   输入
     * @param resume  是否为恢复执行
     * @return 运行结果
     */
    private RunResult execute(Session session, Input input, boolean resume) {
        MutableSession mutableSession = requireMutable(session);
        Agent agent = sessionAgents.computeIfAbsent(mutableSession.id(),
                ignored -> services.agents().load(mutableSession.agentId(), mutableSession.agentVersion()));
        long startNanos = System.nanoTime();
        Slots slots = services.contexts().slots(mutableSession.id());
        Workspace workspace = services.contexts().workspace(mutableSession.id());
        Input effectiveInput = input == null ? Input.empty() : input;
        mutableSession.state(SessionState.RUNNING);
        if (!effectiveInput.slots().isEmpty()) {
            slots.putAll(effectiveInput.slots());
        }
        if (!effectiveInput.text().isBlank()) {
            mutableSession.appendMessage(Message.user(effectiveInput.text()));
        }
        if (resume) {
            publish(Topics.SESSION_RESUMED, mutableSession, Map.of("cursor", String.valueOf(mutableSession.cursor().nodeId())));
        }
        // 开始追踪
        TraceContext trace = startTrace(mutableSession, agent, resume);
        try {
            // 执行输入守卫与过滤
            Input filtered = this.guardAndFilterInput(mutableSession, agent, effectiveInput);

            WorkflowExecution execution = WorkflowExecution
                    .of(agent, mutableSession, agent.workflow(), slots, workspace, filtered)
                    .withTrace(trace, trace == null ? null : trace.rootSpan());
            RunOutcome outcome = this.runWorkflow(mutableSession, agent, execution);
            String output = handleOutput(mutableSession, agent, outcome);
            outcome.messages().forEach(mutableSession::appendMessage);
            applyOutcome(mutableSession, outcome, output);
            services.contexts().persist(mutableSession);
            finishTrace(trace, SessionState.FAILED == outcome.state(), outcome.error());
            publishOutcomeEvent(outcome, mutableSession);
            return RunResult.of(mutableSession.id(), outcome.state(), output)
                    .withCursor(mutableSession.cursor())
                    .withMessages(mutableSession.messages())
                    .withUsage(outcome.usage())
                    .withDuration(Duration.ofNanos(System.nanoTime() - startNanos))
                    .withSlots(slots.asMap())
                    .withVisitedNodes(outcome.visitedNodes())
                    .withSuspendedNode(outcome.suspendedNode())
                    .withExecutionTrace(outcome.trace())
                    .withError(outcome.error());
        } catch (LoopBreakException breakLoop) {
            return completeEarly(mutableSession, slots, trace, startNanos, breakLoop);
        } catch (GuardDeniedException e) {
            return fail(mutableSession, slots, trace, startNanos, agent, e);
        } catch (Exception e) {
            return fail(mutableSession, slots, trace, startNanos, agent, e);
        }
    }

    /**
     * 执行 before_agent 守卫与输入过滤。
     *
     * @param session 会话
     * @param agent   Agent 实例
     * @param input   原始输入
     * @return 过滤后的输入
     */
    private Input guardAndFilterInput(MutableSession session, Agent agent, Input input) {
        if (services.middleware() == null) {
            return input;
        }
        ResolvedPolicy policy = agentPolicy(agent);
        String guarded = guardAgentInput(session, agent, input, policy);
        String filtered = filterAgentInput(session, agent, guarded, policy);
        return filtered == null || filtered.equals(input.text()) ? input
                : new Input(filtered, input.payload(), input.slots());
    }

    /**
     * 执行 before_agent 守卫。
     *
     * <p>Agent 的输入载荷以文本呈现：内容类守卫可以直接检查，改写结果也会被采纳。</p>
     *
     * @param session 会话
     * @param agent   Agent 实例
     * @param input   原始输入
     * @param policy  Agent 级解析结果，可为 null
     * @return 守卫允许或改写后的文本
     */
    private String guardAgentInput(MutableSession session, Agent agent, Input input, ResolvedPolicy policy) {
        GuardContext context = GuardContext.of(GuardPhase.BEFORE_AGENT, input.text())
                .withSession(session.id(), session.tenantId())
                .withOwner(agent.id(), null)
                .withSlots(services.contexts().slots(session.id()))
                .withAttributes(Map.of("agentVersion", agent.version(), "input", input));
        Object allowed = policy == null
                ? services.middleware().requireGuard(GuardPhase.BEFORE_AGENT, context)
                : PolicyGuards.require(policy, GuardPhase.BEFORE_AGENT, context, services.events());
        return allowed instanceof String rewritten ? rewritten : input.text();
    }

    /**
     * 执行 before_agent_input 过滤。
     *
     * @param session 会话
     * @param agent   Agent 实例
     * @param text    守卫处理后的文本
     * @param policy  Agent 级解析结果，可为 null
     * @return 过滤后的文本，过滤器未改写时返回 null
     */
    private String filterAgentInput(MutableSession session, Agent agent, String text, ResolvedPolicy policy) {
        FilterContext context = FilterContext.of(FilterPhase.BEFORE_AGENT_INPUT)
                .withOwner(session.id(), null)
                .withAttributes(Map.of("agentId", agent.id()));
        return policy == null
                ? services.middleware().filter(FilterPhase.BEFORE_AGENT_INPUT, text, context)
                : services.middleware().filter(FilterPhase.BEFORE_AGENT_INPUT, text, context, policy.filters());
    }

    /**
     * 通过拦截链执行工作流。
     *
     * @param session   会话
     * @param agent     Agent 实例
     * @param execution 工作流执行输入
     * @return 运行结果
     * @throws Exception 执行失败时抛出
     */
    private RunOutcome runWorkflow(MutableSession session, Agent agent, WorkflowExecution execution)
            throws Exception {
        if (services.middleware() == null) {
            return services.workflowRuntime().run(execution);
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(InterceptorAttributes.AGENT_ID, agent.id());
        attributes.put(InterceptorAttributes.TRACE, execution.trace());
        attributes.put(InterceptorAttributes.TRACE_PARENT,
                execution.trace() == null ? null : execution.trace().rootSpan());
        // 根 span 代表一次 Agent 运行，这里的 span 代表其中的一次工作流执行，二者不重名
        attributes.put(InterceptorAttributes.SPAN_NAME, "workflow:" + agent.workflow().id());
        attributes.put(InterceptorAttributes.SPAN_KIND, com.agentframework.crosscutting.trace.SpanKind.WORKFLOW);
        InterceptorContext context = InterceptorContext.of(InterceptorPhase.AROUND_AGENT, "agent:" + agent.id())
                .withOwner(session.id(), null)
                .withAttributes(attributes);
        return services.middleware().intercept(InterceptorPhase.AROUND_AGENT, context,
                () -> services.workflowRuntime().run(execution));
    }

    /**
     * 输出阶段的过滤与守卫。
     *
     * @param session 会话
     * @param agent   Agent 实例
     * @param outcome 工作流结果
     * @return 最终输出
     */
    private String handleOutput(MutableSession session, Agent agent, RunOutcome outcome) {
        String output = outcome.output();
        if (services.middleware() == null) {
            return output;
        }
        ResolvedPolicy policy = agentPolicy(agent);
        FilterContext filterContext = FilterContext.of(FilterPhase.BEFORE_OUTPUT).withOwner(session.id(), null);
        String filtered = policy == null
                ? services.middleware().filter(FilterPhase.BEFORE_OUTPUT, output, filterContext)
                : services.middleware().filter(FilterPhase.BEFORE_OUTPUT, output, filterContext, policy.filters());
        GuardContext guardContext = GuardContext.of(GuardPhase.BEFORE_OUTPUT, filtered)
                .withSession(session.id(), session.tenantId())
                .withOwner(session.agentId(), null)
                .withSlots(services.contexts().slots(session.id()));
        Object allowed;
        try {
            allowed = policy == null
                    ? services.middleware().requireGuard(GuardPhase.BEFORE_OUTPUT, guardContext)
                    : PolicyGuards.require(policy, GuardPhase.BEFORE_OUTPUT, guardContext, services.events());
        } catch (LoopBreakException breakLoop) {
            publish(Topics.LOOP_BREAK, session, Map.of("reason", String.valueOf(breakLoop.reason())));
            return filtered;
        }
        String finalOutput = allowed == null ? filtered : String.valueOf(allowed);
        if (policy == null) {
            services.middleware().filter(FilterPhase.AFTER_OUTPUT, finalOutput, filterContext);
        } else {
            services.middleware().filter(FilterPhase.AFTER_OUTPUT, finalOutput, filterContext, policy.filters());
        }
        return finalOutput;
    }

    /**
     * Agent 作用域的循环中断：优雅停止，保留当前输出。
     *
     * @param session   会话
     * @param slots     槽位
     * @param trace     链路上下文
     * @param startNanos 开始时间
     * @param breakLoop 中断信号
     * @return 完成态运行结果
     */
    private RunResult completeEarly(MutableSession session, Slots slots,
            com.agentframework.crosscutting.trace.TraceContext trace, long startNanos,
            LoopBreakException breakLoop) {
        String output = session.output() == null ? "" : session.output();
        session.output(output);
        session.state(SessionState.COMPLETED);
        services.contexts().persist(session);
        finishTrace(trace, false, null);
        publish(Topics.LOOP_BREAK, session, Map.of("reason", String.valueOf(breakLoop.reason())));
        publish(Topics.SESSION_COMPLETED, session, Map.of("nodes", 0, "reason", "loop.break"));
        return RunResult.of(session.id(), SessionState.COMPLETED, output)
                .withCursor(session.cursor())
                .withMessages(session.messages())
                .withDuration(java.time.Duration.ofNanos(System.nanoTime() - startNanos))
                .withSlots(slots.asMap())
                .withVisitedNodes(List.of());
    }

    /**
     * 解析 Agent 级作用域链（全局 + Agent + Workflow）。
     *
     * @param agent Agent 实例
     * @return 解析结果，未配置解析器时返回 null
     */
    private ResolvedPolicy agentPolicy(Agent agent) {
        PolicyResolver resolver = services.policyResolver();
        if (resolver == null || agent == null || agent.workflow() == null) {
            return null;
        }
        return resolver.resolve(PolicyScopeChain.of(PolicyScope.global(), PolicyScope.agent(agent.definition()),
                PolicyScope.workflow(agent.workflow())));
    }

    /**
     * 把运行结果写回会话。
     *
     * @param session 会话
     * @param outcome 运行结果
     * @param output  最终输出
     */
    private void applyOutcome(MutableSession session, RunOutcome outcome, String output) {
        session.cursor(outcome.cursor());
        switch (outcome.state()) {
            case COMPLETED -> {
                session.output(output);
                session.state(SessionState.COMPLETED);
            }
            case SUSPENDED -> {
                session.output(output);
                session.state(SessionState.SUSPENDED);
            }
            case FAILED, CANCELLED -> {
                session.error(outcome.error());
                session.state(outcome.state());
            }
            default -> session.state(outcome.state());
        }
    }

    /**
     * 失败收尾：记录状态、事件与追踪。
     *
     * @param session    会话
     * @param slots      槽位
     * @param trace      链路上下文
     * @param startNanos 开始时间
     * @param agent      Agent 实例
     * @param cause      失败原因
     * @return 失败结果
     */
    private RunResult fail(MutableSession session, Slots slots,
            com.agentframework.crosscutting.trace.TraceContext trace, long startNanos, Agent agent, Exception cause) {
        String message = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        session.error(message);
        session.state(SessionState.FAILED);
        services.contexts().persist(session);
        finishTrace(trace, true, message);
        if (cause instanceof GuardDeniedException denied) {
            publish(Topics.GUARD_DENIED, session,
                    Map.of("guard", denied.guardName(), "phase", denied.phase().name(), "reason",
                            String.valueOf(denied.getMessage())));
        }
        publish(Topics.SESSION_FAILED, session, Map.of("agentId", agent.id(), "error", message));
        return RunResult.of(session.id(), SessionState.FAILED, "")
                .withCursor(session.cursor())
                .withMessages(session.messages())
                .withDuration(java.time.Duration.ofNanos(System.nanoTime() - startNanos))
                .withSlots(slots.asMap())
                .withError(message);
    }

    /**
     * 开启链路追踪。
     *
     * @param session 会话
     * @param agent   Agent 实例
     * @param resume  是否为恢复执行
     * @return 链路上下文，未启用追踪时返回 null
     */
    private TraceContext startTrace(MutableSession session, Agent agent,
            boolean resume) {
        if (services.tracer() == null || !services.tracer().enabled()) {
            return null;
        }
        return services.tracer().startTrace(session.traceId(), "agent:" + agent.id(),
                SpanKind.AGENT,
                Map.of("sessionId", session.id(), "resume", resume, "agentVersion", agent.version()));
    }

    /**
     * 结束链路并导出。
     *
     * @param trace   链路上下文
     * @param failed  是否失败
     * @param error   错误信息
     */
    private void finishTrace(TraceContext trace, boolean failed, String error) {
        if (trace == null || !trace.active() || services.tracer() == null) {
            return;
        }
        if (failed) {
            services.tracer().endSpan(trace.rootSpan(), new IllegalStateException(error));
        } else {
            services.tracer().endSpan(trace.rootSpan());
        }
        services.tracer().flush();
    }

    /**
     * 发布运行结束事件。
     *
     * @param outcome 运行结果
     * @param session 会话
     */
    private void publishOutcomeEvent(RunOutcome outcome, Session session) {
        switch (outcome.state()) {
            // 运行结束事件
            case COMPLETED -> publish(Topics.SESSION_COMPLETED, session,
                    Map.of("nodes", outcome.visitedNodes().size(), "tokens", outcome.usage().total()));
            // 运行中断事件
            case SUSPENDED -> publish(Topics.SESSION_SUSPENDED, session,
                    Map.of("nodeId", String.valueOf(outcome.suspendedNode())));
            // 运行失败事件
            case FAILED -> publish(Topics.SESSION_FAILED, session,
                    Map.of("error", String.valueOf(outcome.error())));
            default -> {
                // 其它状态无需额外事件
            }
        }
    }

    /**
     * 发布事件。
     *
     * @param type    事件类型
     * @param session 会话
     * @param payload 事件负载
     */
    private void publish(String type, Session session, Map<String, Object> payload) {
        if (services.events() == null) {
            return;
        }
        Event event = Event.of(type, session.id(), payload).withTenant(session.tenantId())
                .withTrace(session.traceId());
        services.events().publish(event);
    }

    /**
     * 转换为可写会话视图。
     *
     * @param session 会话
     * @return 可写会话
     */
    private MutableSession requireMutable(Session session) {
        if (session instanceof MutableSession mutable) {
            return mutable;
        }
        throw new IllegalArgumentException("会话必须是引擎创建的实例（MutableSession）："
                + (session == null ? "null" : session.getClass().getName()));
    }

    @Override
    public EngineConfig config() {
        return services.config();
    }

    @Override
    public AgentManager agents() {
        return services.agents();
    }

    @Override
    public ContextManager contexts() {
        return services.contexts();
    }

    @Override
    public ToolRegistry tools() {
        return services.tools();
    }

    @Override
    public PromptManager prompts() {
        return services.prompts();
    }

    @Override
    public DefinitionSource definitions() {
        return services.definitions();
    }

    @Override
    public ExtensionRegistry extensions() {
        return services.extensions();
    }

    @Override
    public com.agentframework.crosscutting.trace.Tracer tracer() {
        return services.tracer();
    }

    @Override
    public com.agentframework.crosscutting.metrics.Metrics metrics() {
        return services.metrics();
    }

    @Override
    public EventBus events() {
        return services.events();
    }

    @Override
    public PluginRuntime plugins() {
        return services.plugins();
    }

    @Override
    public RecoveryManager recovery() {
        return services.recovery();
    }

    @Override
    public PersistenceManager persistence() {
        return services.persistence();
    }

    @Override
    public java.util.Map<String, com.agentframework.engine.policy.RegionMetrics> regionMetrics(String sessionId) {
        return services.regionMetrics().metrics(sessionId);
    }

    @Override
    public List<CheckpointEntry> history(String sessionId) {
        return services.persistence().history(sessionId);
    }

    @Override
    public RollbackResult rollback(String sessionId, int toStep) {
        CheckpointEntry entry = services.persistence().checkpointAt(sessionId, toStep)
                .orElseThrow(() -> new java.util.NoSuchElementException("会话 " + sessionId + " 没有第 " + toStep
                        + " 步的检查点，可用步号："
                        + services.persistence().history(sessionId).stream()
                                .map(CheckpointEntry::step).toList()));
        SessionRecord record = entry.session().withState(SessionState.SUSPENDED, java.time.Instant.now());
        Session restored = services.contexts().restore(record, entry.slots());
        int dropped = services.persistence().truncateHistory(sessionId, toStep);
        publish(Topics.SESSION_SUSPENDED, restored,
                Map.of("reason", "rollback", "step", toStep, "droppedCheckpoints", dropped));
        Map<String, Object> slotValues = new LinkedHashMap<>();
        entry.slots().values().forEach((key, value) -> slotValues.put(key, value.value()));
        return new RollbackResult(sessionId, toStep, record.cursor(), slotValues, dropped, null);
    }

    @Override
    public void close() {
        services.persistence().flushTrace();
        services.plugins().close();
        services.extensions().closeAll();
        services.scheduler().close();
    }
}
