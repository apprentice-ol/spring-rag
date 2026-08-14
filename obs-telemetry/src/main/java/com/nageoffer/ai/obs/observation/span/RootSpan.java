package com.nageoffer.ai.obs.observation.span;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import org.slf4j.MDC;

/**
 * 无父新 trace 根 span 后端：走 OTel {@code SpanBuilder.setNoParent()} + {@code makeCurrent()}，不经 micrometer Observation。
 *
 * <p><b>所属维度</b>：②backend（策略·具体实现 B）。</p>
 *
 * <p><b>职责</b>：强制开一条无父的新 trace，openScope，写 MDC（step/step_id/traceId）。供后台任务或"已 in-trace 需脱钩"用。</p>
 *
 * <p><b>协作</b>：由 {@code ObsTemplate.openTrace} / {@code trigger.ObservedStepAspect}（{@code kind=ROOT}）经 {@link #createAndOpen} 创建。tracer 名由 {@code TelemetryProperties.tracerName} 传入（默认 "obs"）。</p>
 *
 * <p><b>后端归属（后端无关）</b>：操作 OTel span，经 Collector 分发到多后端。</p>
 */
public final class RootSpan implements SpanSession {

    private final Span span;
    private final String spanId;
    private final io.opentelemetry.context.Scope scope;
    /** 开 scope 前的 MDC 旧值（如外层请求已有 step/traceId），closeScope 时恢复——不清掉外层键。 */
    private final String prevStep;
    private final String prevStepId;
    private final String prevTraceId;

    private RootSpan(Span span, String spanId, io.opentelemetry.context.Scope scope,
                     String prevStep, String prevStepId, String prevTraceId) {
        this.span = span;
        this.spanId = spanId;
        this.scope = scope;
        this.prevStep = prevStep;
        this.prevStepId = prevStepId;
        this.prevTraceId = prevTraceId;
    }

    /** 开一个无父的 trace 根 span 并 makeCurrent（写 MDC step/step_id/traceId，旧值保存供恢复）。tracerName 来自配置。 */
    public static RootSpan createAndOpen(String name, OpenTelemetry otel, String tracerName) {
        Span span = otel.getTracer(tracerName)
                .spanBuilder(name)
                .setNoParent()
                .startSpan();
        io.opentelemetry.context.Scope scope = span.makeCurrent();
        String spanId = span.getSpanContext().getSpanId();
        String traceId = span.getSpanContext().getTraceId();
        String prevStep = MDC.get("step");
        String prevStepId = MDC.get("step_id");
        String prevTraceId = MDC.get("traceId");
        MDC.put("step", name);
        MDC.put("step_id", spanId);
        MDC.put("traceId", traceId);
        return new RootSpan(span, spanId, scope, prevStep, prevStepId, prevTraceId);
    }

    @Override
    public Span getSpan() {
        return span;
    }

    @Override
    public String getSpanId() {
        return spanId;
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
        // root span 无 Observation KeyValue 机制，退化为普通 setAttribute
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

    @Override
    public void closeScope() {
        try {
            scope.close();
        } catch (Exception ignored) {
        }
        // 恢复外层键（ROOT 开在已有请求/step 内时不误清外层 traceId）；外层无值才真正移除
        restoreMdc("step", prevStep);
        restoreMdc("step_id", prevStepId);
        restoreMdc("traceId", prevTraceId);
    }

    private static void restoreMdc(String key, String prev) {
        if (prev != null) {
            MDC.put(key, prev);
        } else {
            MDC.remove(key);
        }
    }

    @Override
    public void end() {
        try {
            span.end();
        } catch (Exception ignored) {
        }
    }
}
