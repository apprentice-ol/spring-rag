package com.jjx.ai.llmobservability.observation.propagation;

import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;

/**
 * 跨线程上下文传播工具（封装 micrometer {@link ContextSnapshot}，让业务侧不直接 import micrometer）。
 *
 * <p><b>所属维度</b>：telemetry 共享（传播工具）。</p>
 *
 * <p><b>职责</b>：{@link #wrap(Runnable)} 包装任务，使其在新线程执行时恢复当前线程的 MDC / OTel Context /
 * 对话上下文（即 {@code ContextPropagationConfiguration} 注册的全部 accessor）——虚拟线程、线程池跨线程传播的统一入口。</p>
 *
 * <p><b>为何封装</b>：把 {@code ContextSnapshot.captureAll().wrap(...)} 的 micrometer API 收敛在 telemetry，
 * 业务侧（如 ChatController 的虚拟线程、EvalRunner 的并发任务）只调本工具，不直接依赖 micrometer context-propagation。</p>
 */
public final class ContextPropagator {

    private ContextPropagator() {
    }

    /** 包装 Runnable：在新线程执行时自动恢复当前线程的 MDC/OTel/对话上下文。 */
    public static Runnable wrap(Runnable task) {
        ContextSnapshotFactory contextSnapshotFactory = ContextSnapshotFactory.builder().build();
        return contextSnapshotFactory.captureAll().wrap(task);
    }
}
