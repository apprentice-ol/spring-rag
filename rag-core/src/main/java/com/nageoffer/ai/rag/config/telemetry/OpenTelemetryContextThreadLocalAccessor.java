package com.nageoffer.ai.rag.config.telemetry;

import io.micrometer.context.ThreadLocalAccessor;
import io.opentelemetry.context.Context;

/**
 * 把 OpenTelemetry Context（{@link Context#current()}）接入 Micrometer Context Propagation，
 * 使 {@code ContextSnapshot} / {@code ContextPropagatingTaskDecorator} /
 * {@code Hooks.enableAutomaticContextPropagation} 能跨线程传播 OTel Context（当前 trace/span + baggage），
 * 从而保证子线程 / 池线程 / Flux 回调线程里新开的 step span 挂在父 trace 下。
 *
 * <p>替代手写 {@code ContextPropagator} 中 {@code Context.current()} 的捕获与 {@code makeCurrent()} 的恢复。
 * 与 micrometer-observation 自动注册的 {@code ObservationThreadLocalAccessor} 并存且幂等（同一 ctx
 * 重复 makeCurrent 返回 NoopScope，无副作用）。
 *
 * <p><b>Scope 语义</b>：{@link Context#makeCurrent()} 返回的 {@link io.opentelemetry.context.Scope} 在此丢弃。
 * 默认 {@code ThreadLocalContextStorage} 下安全——ThreadLocal 只保留最新值，丢弃的 ScopeImpl 仅持 previous
 * 引用被 GC，无栈累积、无泄露；同 ctx 重复 makeCurrent 返回 NoopScope，零成本。
 * <b>不可与 OTel {@code StrictContextStorage}（断言模式）同时使用</b>，启用前需 review。
 *
 * <p>{@code restore(V)} / {@code restore()} 走 {@link ThreadLocalAccessor} 默认实现（分别委托
 * {@code setValue(V)} / {@code setValue()}），故无需单独覆写。
 */
public final class OpenTelemetryContextThreadLocalAccessor implements ThreadLocalAccessor<Context> {

    /** ContextRegistry 内以 key 去重 / 选择，全局唯一即可。 */
    public static final String KEY = "otel.context";

    @Override
    public Object key() {
        return KEY;
    }

    /** 捕获当前线程 OTel Context（含当前 span / baggage）。{@code Context.current()} 永不返回 null。 */
    @Override
    public Context getValue() {
        return Context.current();
    }

    /** 在目标线程把 OTel Context 设为 current（应用捕获快照）。丢弃返回的 Scope（见类注释）。 */
    @Override
    public void setValue(Context value) {
        value.makeCurrent();
    }

    /** 清空：回退到 root Context。必须覆写——否则默认链路 {@code restore()→setValue()→reset()} 会抛 IllegalStateException。 */
    @Override
    public void setValue() {
        Context.root().makeCurrent();
    }
}
