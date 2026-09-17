package com.jjx.customer.platform.agent.framework.agent;

import com.jjx.customer.platform.agent.framework.node.AgentInvocation;
import com.jjx.customer.platform.agent.framework.node.AgentInvoker;
import com.jjx.customer.platform.agent.framework.plan.ExecutionPlan;
import com.jjx.customer.platform.agent.framework.result.ExecutionMetadataSink;
import com.jjx.customer.platform.agent.framework.result.ExecutionResult;
import com.jjx.customer.platform.agent.framework.result.ExecutionResultValidator;
import com.jjx.customer.platform.agent.framework.spi.*;
import com.jjx.customer.platform.agent.framework.trace.AgentTrace;
import com.jjx.customer.platform.agent.framework.trace.ExecutionTrace;
import com.jjx.customer.platform.agent.framework.workflow.WorkflowDriver;

import java.util.List;

/**
 * Workflow 引擎（对外唯一执行入口）。
 *
 * <p>职责（设计文档 §2/§3）：唯一执行入口——调用主 Agent、驱动其 Workflow、
 * 迭代阶段、托管预算/错误策略/观测/校验/会话、调度子 Agent、装配执行结果与元数据；
 * 顶层执行过拦截器（D5：可否决请求，不可改流程控制）。</p>
 *
 * <p>本类同时被 {@link WorkflowEngineAgentInvoker} 适配成 {@link AgentInvoker} 角色
 * （子 Agent 重入）；引擎自身只保留执行入口职责，不隐式兼任角色。</p>
 *
 * <p><b>骨架与生命周期</b>（Spring 范式）：本类是执行骨架的宿主——持有有序的
 * {@link ExecutionListener}（观察者）与 {@link ExecutionInterceptor}（可否决的拦截器，MVC HandlerInterceptor 范式），
 * 实现 {@link FrameworkLifecycle}（装配完成后 {@code start()}、停机前 {@code stop()}）；
 * 横切关注点（会话落库、观测推送、审计）以监听器接入，引擎本体不写 if。
 * 骨架阶段（{@link ExecutionPhase}）由驱动发布、引擎转发，扩展点按 {@link Ordered} 排序。</p>
 *
 * <p><b>引擎边界</b>：不写业务规则、不认识具体工具；执行计划与节点执行的实现分别由
 * {@link ExecutionPlanner} 与 {@link WorkflowDriver} 承担。</p>
 */
public class WorkflowEngine implements FrameworkLifecycle {

    private final ExecutionPlanner planner;
    private final WorkflowDriver driver;
    private final List<ExecutionInterceptor> interceptors;
    private final List<ExecutionListener> listeners;
    /** 本引擎扮演的 {@link AgentInvoker} 角色（Adapter：{@link WorkflowEngineAgentInvoker}）。 */
    private final AgentInvoker agentInvoker;
    private volatile boolean running;

    public WorkflowEngine(ExecutionPlanner planner, WorkflowDriver driver, AgentRegistry agentRegistry) {
        this(planner, driver, agentRegistry, 3);
    }

    public WorkflowEngine(ExecutionPlanner planner, WorkflowDriver driver,
                          AgentRegistry agentRegistry, int maxDepth) {
        this(planner, driver, agentRegistry, List.of(), maxDepth);
    }

    public WorkflowEngine(ExecutionPlanner planner, WorkflowDriver driver, AgentRegistry agentRegistry,
                          List<ExecutionInterceptor> interceptors, int maxDepth) {
        this(planner, driver, agentRegistry, interceptors, maxDepth, List.of());
    }

    public WorkflowEngine(ExecutionPlanner planner, WorkflowDriver driver, AgentRegistry agentRegistry,
                          List<ExecutionInterceptor> interceptors, int maxDepth,
                          List<ExecutionListener> listeners) {
        this.planner = planner;
        this.driver = driver;
        this.interceptors = Ordered.sorted(interceptors);
        this.listeners = Ordered.sorted(listeners);
        this.agentInvoker = new WorkflowEngineAgentInvoker(this, agentRegistry, maxDepth);
    }

    /** 主入口：装配执行计划 → 驱动执行 → 结果契约校验。返回永不为 null。 */
    public ExecutionResult execute(AgentRequest request) {
        return execute(planner.plan(request), ExecutionMetadataSink.NOOP);
    }

    /** 带元数据出口的执行：指纹在执行开始前即流出（缓存 key 的先决信息）。 */
    public ExecutionResult execute(AgentRequest request, ExecutionMetadataSink sink) {
        return execute(planner.plan(request), sink);
    }

    /** 已装配计划的直接执行（顶层）。 */
    public ExecutionResult execute(ExecutionPlan plan, ExecutionMetadataSink sink) {
        // 顶层创建共享账本挂进调用上下文——子 Agent 重入沿链共享，消耗逐笔上卷（§4/D6）
        return execute(plan, sink, new AgentInvocation(0,
                List.of(plan.agent().id()), new ExecutionTrace(),
                -1, Long.MAX_VALUE));
    }

    /** 带执行级上下文的执行（顶层与子 Agent 重入共用）。 */
    public ExecutionResult execute(ExecutionPlan plan, ExecutionMetadataSink sink, AgentInvocation invocation) {
        ExecutionMetadataSink safeSink = sink == null ? ExecutionMetadataSink.NOOP : sink;
        boolean top = invocation.depth() == 0;
        if (top) {
            notifyStart(plan, invocation);
        }
        ExecutionResult result;
        try {
            result = doExecute(plan, safeSink, invocation, top);
        } catch (RuntimeException error) {
            if (top) {
                notifyError(plan, error);
            }
            throw error;
        }
        if (top) {
            notifyAfter(plan, result);
            notifyEnd(plan, result);
        }
        return result;
    }

    /** 骨架推进：拦截器裁决（可否决）→ 驱动执行（阶段事件经 {@link ExecutionEvents} 发布）→ 结果契约校验。 */
    private ExecutionResult doExecute(ExecutionPlan plan, ExecutionMetadataSink safeSink,
                                      AgentInvocation invocation, boolean top) {
        if (top) {
            for (ExecutionInterceptor interceptor : interceptors) {
                String veto = interceptor.beforeExecution(plan);
                if (veto != null) {
                    return ExecutionResult.escalate(veto, plan.fingerprint(),
                            AgentTrace.empty(plan.agent().id(), plan.workflow().id()));
                }
            }
        }
        safeSink.onFingerprint(plan.fingerprint());
        ExecutionResult result = driver.drive(plan, safeSink, agentInvoker, invocation, events(plan));
        ExecutionResultValidator.validate(result, plan.capabilities());
        return result;
    }

    // ==================== 生命周期（FrameworkLifecycle + 监听器转发） ====================

    @Override
    public void start() {
        running = true;
        for (ExecutionListener listener : listeners) {
            invokeQuietly(listener::onEngineStart);
        }
    }

    @Override
    public void stop() {
        running = false;
        for (ExecutionListener listener : listeners) {
            invokeQuietly(listener::onEngineStop);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 骨架阶段事件发布口（交给驱动；无监听器时零开销）。 */
    private ExecutionEvents events(ExecutionPlan plan) {
        return listeners.isEmpty() ? ExecutionEvents.NOOP
                : (phase, detail) -> {
                    for (ExecutionListener listener : listeners) {
                        invokeQuietly(() -> listener.onPhase(phase, plan, detail));
                    }
                };
    }

    private void notifyStart(ExecutionPlan plan, AgentInvocation invocation) {
        for (ExecutionListener listener : listeners) {
            invokeQuietly(() -> listener.onExecutionStart(plan, invocation));
        }
    }

    private void notifyEnd(ExecutionPlan plan, ExecutionResult result) {
        for (ExecutionListener listener : listeners) {
            invokeQuietly(() -> listener.onExecutionEnd(plan, result));
        }
    }

    private void notifyError(ExecutionPlan plan, Throwable error) {
        for (ExecutionListener listener : listeners) {
            invokeQuietly(() -> listener.onExecutionError(plan, error));
        }
    }

    /** 拦截器后置回调（终态结果已出）。 */
    private void notifyAfter(ExecutionPlan plan, ExecutionResult result) {
        for (ExecutionInterceptor interceptor : interceptors) {
            invokeQuietly(() -> interceptor.afterExecution(plan, result));
        }
    }

    /** 扩展点回调统一兜底：回调异常不得影响主链路（D5：观测/横切不可改流程控制）。 */
    private static void invokeQuietly(Runnable callback) {
        try {
            callback.run();
        } catch (Exception ignored) {
            // 观察者异常只忽略：主链路的正确性不依赖扩展点
        }
    }

    /** 本引擎扮演的 {@link AgentInvoker} 角色实例（{@code AGENT_CALL} 节点的调用入口）。 */
    public AgentInvoker agentInvoker() {
        return agentInvoker;
    }

    /**
     * 子 Agent 重入执行内核（{@link WorkflowEngineAgentInvoker} 专用入口）。
     *
     * <p>入参上下文由适配层完成环检测、深度校验与派生（深度 +1 / 链追加 / 账本共享 /
     * 预算切分 / 截止收紧）；本方法只装配子执行计划并复用同一执行内核。</p>
     */
    ExecutionResult reenter(Agent agent, AgentRequest request, ExecutionMetadataSink sink,
                            AgentInvocation invocation) {
        return execute(planner.planFor(agent, request), sink, invocation);
    }
}
