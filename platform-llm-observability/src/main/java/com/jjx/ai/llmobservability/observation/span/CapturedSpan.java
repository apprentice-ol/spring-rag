package com.jjx.ai.llmobservability.observation.span;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;

/**
 * 持固定 {@link Span} 引用的 {@link SpanWriter}（无生命周期，只写不关）。
 *
 * <p><b>所属维度</b>：②backend（策略·具体实现 D，只实现 SpanWriter）。</p>
 *
 * <p><b>职责</b>：把数据写到一个构造期捕获的固定 span，而非 {@code Span.current()}。用于对话级 output——回答在 Reactor 流式回调线程产生，{@code Span.current()} 不保证恢复为根 span，持引用才稳。</p>
 *
 * <p><b>协作</b>：被 {@code event.ConversationContext} 持有（包装 HTTP server span），经 sink 链写 trace 级 IO。</p>
 *
 * <p><b>不做什么</b>：不开/关 span；区别于 {@link CurrentSpan}（实时取 Span.current()，只适合同步请求线程）。</p>
 */
public final class CapturedSpan implements SpanWriter {

    private final Span span;

    public CapturedSpan(Span span) {
        this.span = span;
    }

    @Override
    public void setAttribute(String key, String value) {
        try {
            span.setAttribute(key, value);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void setTag(String key, String value) {
        try {
            span.setAttribute(key, value == null ? "" : value);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void recordError(Throwable t) {
        try {
            span.recordException(t);
            span.setStatus(StatusCode.ERROR);
        } catch (Exception ignored) {
        }
    }
}
