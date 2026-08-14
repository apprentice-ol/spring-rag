package com.nageoffer.ai.obs.observation.span;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.trace.Span;
import org.slf4j.MDC;

/**
 * 挂当前 ambient 父 span 下的步骤后端：走 micrometer {@link Observation}（→ OTel child span）。
 *
 * <p><b>所属维度</b>：②backend（策略·具体实现 A）。</p>
 *
 * <p><b>职责</b>：开一个挂在当前父下的 child span，openScope，写 MDC（step/step_id）。供普通步骤（{@code @ObservedStep} 默认 / {@code openStep}）用。</p>
 *
 * <p><b>协作</b>：由 {@code ObsTemplate.openStep} / {@code trigger.ObservedStepAspect}（默认 kind=STEP）经 {@link #createAndOpen} 创建，交给 {@code ObsSpan} 持有。</p>
 *
 * <p><b>后端归属（后端无关）</b>：操作 OTel span，经 Collector 分发到多后端，不专属任一。</p>
 */
public final class ObservationSpan implements SpanSession {

    private final Observation observation;
    private final Observation.Scope scope;
    private final Span span;
    private final String spanId;

    private ObservationSpan(Observation observation, Span span, String spanId, Observation.Scope scope) {
        this.observation = observation;
        this.span = span;
        this.spanId = spanId;
        this.scope = scope;
    }

    /** 开一个挂当前父的 step span 并 openScope（makeCurrent + 写 MDC step/step_id）。 */
    public static ObservationSpan createAndOpen(String name, ObservationRegistry registry) {
        Observation observation = Observation.createNotStarted(name, registry)
                .lowCardinalityKeyValue("step", name)
                .start();
        Observation.Scope scope = observation.openScope();
        Span span = Span.current();
        String spanId = span.getSpanContext().getSpanId();
        MDC.put("step", name);
        MDC.put("step_id", spanId);
        return new ObservationSpan(observation, span, spanId, scope);
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
        try {
            observation.lowCardinalityKeyValue(key, value == null ? "" : value);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void recordError(Throwable t) {
        try {
            observation.error(t);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void closeScope() {
        try {
            scope.close();
        } catch (Exception ignored) {
        }
        MDC.remove("step");
        MDC.remove("step_id");
    }

    @Override
    public void end() {
        try {
            observation.stop();
        } catch (Exception ignored) {
        }
    }
}
