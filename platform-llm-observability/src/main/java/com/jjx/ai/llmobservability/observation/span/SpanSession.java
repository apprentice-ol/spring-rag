package com.jjx.ai.llmobservability.observation.span;

import io.opentelemetry.api.trace.Span;

/**
 * Span 后端策略：封装"怎么开/关 span + 怎么写属性"，屏蔽 Observation（挂当前父）与 OTel 直开（setNoParent 新 trace）的差异。
 *
 * <p><b>所属维度</b>：②backend（策略模式）。{@link ObservationSpan} 与 {@link RootSpan} 是两个具体策略。</p>
 *
 * <p><b>职责</b>：在"已 open"的 span 上提供"写属性 / 记异常 / 关 scope / 结束 span"。open 动作在各实现的静态工厂 {@code createAndOpen} 完成。</p>
 *
 * <p><b>协作</b>：被 {@code event.TelemetrySpan} 持有（每 handle 一个实例）；作为 {@link SpanWriter} 传给 sink 链。</p>
 *
 * <p><b>不做什么</b>：不发结构化日志、不管 MDC ambient 标签、不感知业务。</p>
 */
public interface SpanSession extends SpanWriter {

    /** 构造期捕获的 OTel span 引用（跨线程 setAttribute 用，不依赖 Span.current()）。 */
    Span getSpan();

    /** 本后端的 spanId（MDC step_id 用）。 */
    String getSpanId();

    /** 关 scope（不 end span）：关 scope + 恢复 MDC 生命周期键（step/step_id[/traceId]）为外层旧值，外层无值才移除。幂等。 */
    void closeScope();

    /** 结束 span（observation.stop / span.end）。幂等。 */
    void end();
}
