package com.jjx.ai.llmobservability.observation.span;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;

/**
 * 写当前 ambient span（{@code Span.current()}）的 {@link SpanWriter} 单例。无生命周期，只写不关。
 *
 * <p><b>所属维度</b>：②backend（策略·具体实现 C，只实现 SpanWriter）。</p>
 *
 * <p><b>职责</b>：把数据写到当前请求线程的 HTTP server span（= trace 根）。用于"不开独立 root trace、直接写当前 server span"场景。</p>
 *
 * <p><b>协作</b>：{@link SpanWriter#current()} 返回本单例；被门面 {@code TelemetryTemplate.tag/traceInput/traceOutput}（同步请求线程）使用。</p>
 *
 * <p><b>不做什么</b>：不开/关 span（server span 由 HTTP 框架管理）；只应在同步请求线程使用。</p>
 */
public final class CurrentSpan implements SpanWriter {

    static final CurrentSpan INSTANCE = new CurrentSpan();

    private CurrentSpan() {
    }

    @Override
    public void setAttribute(String key, String value) {
        try {
            Span.current().setAttribute(key, value);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void setTag(String key, String value) {
        try {
            Span.current().setAttribute(key, value == null ? "" : value);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void recordError(Throwable t) {
        try {
            Span s = Span.current();
            s.recordException(t);
            s.setStatus(StatusCode.ERROR);
        } catch (Exception ignored) {
        }
    }
}
