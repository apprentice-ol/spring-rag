package com.jjx.ai.llmobservability.observation.propagation;

import io.micrometer.context.ThreadLocalAccessor;
import io.opentelemetry.context.Context;

/**
 * 把 OpenTelemetry Context（{@link Context#current()}）接入 Micrometer Context Propagation。
 *
 * <p><b>所属维度</b>：④传播。</p>
 *
 * <p><b>职责</b>：使 {@code ContextSnapshot} / {@code ContextPropagatingTaskDecorator} / {@code Hooks.enableAutomaticContextPropagation}
 * 能跨线程传播 OTel Context（当前 trace/span + baggage），保证子线程 / 池线程 / Flux 回调线程里新开的 step span 挂在父 trace 下。</p>
 *
 * <p><b>Scope 语义</b>：{@link Context#makeCurrent()} 返回的 Scope 在此丢弃。默认 ThreadLocalContextStorage 下安全——
 * ThreadLocal 只保留最新值，无栈累积。<b>不可与 OTel StrictContextStorage（断言模式）同时使用</b>。</p>
 *
 * <p><b>{@code setValue()}（无参）必须覆写</b>——否则默认链路抛 IllegalStateException。</p>
 */
public final class OpenTelemetryContextAccessor implements ThreadLocalAccessor<Context> {

    public static final String KEY = "otel.context";

    @Override
    public Object key() {
        return KEY;
    }

    @Override
    public Context getValue() {
        return Context.current();
    }

    @Override
    public void setValue(Context value) {
        value.makeCurrent();
    }

    @Override
    public void setValue() {
        Context.root().makeCurrent();
    }
}
