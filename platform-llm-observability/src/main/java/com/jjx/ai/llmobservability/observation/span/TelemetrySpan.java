package com.jjx.ai.llmobservability.observation.span;

import com.jjx.ai.llmobservability.observation.span.SpanSession;
import com.jjx.ai.llmobservability.observation.support.AttributeKeys;
import com.jjx.ai.llmobservability.observation.ObservationPipeline;
import com.jjx.ai.llmobservability.observation.event.TelemetryEvent;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一个观测步骤 / 根的 trace 句柄：封装"开→输入→输出→异常→关闭"的生命周期模板。
 *
 * <p><b>所属维度</b>：②event（Source 与 pipeline 之间的句柄）。固化步骤生命周期骨架，把"底层 span 怎么开/关"
 * 委托给 {@link SpanSession}（策略），把"事件怎么转/发"委托给 {@link ObservationPipeline}（接→转→发）。
 * <b>不做任何数据加工</b>——input/output 只产 {@link TelemetryEvent} 丢进 pipeline，摘要/截断由 processor 链做，
 * 落地由 exporter 链做。</p>
 *
 * <p><b>职责</b>：暴露 {@code tag/input/output/outputRaw/traceInput/traceOutput/error} 链式 API；
 * 两种关闭语义 {@link #close()}（同步）/ {@link #finish()}（流式）。</p>
 *
 * <p><b>协作</b>：由 {@code TelemetryTemplate.openStep/openTrace} 构造；被业务与 {@code source.TelemetryStepAspect} 使用。</p>
 *
 * <p><b>不做什么</b>：不加工 data（processor 的事）；不写 span/发日志（exporter 的事）；不碰 MDC（backend.closeScope 管）。</p>
 *
 * <p><b>线程安全</b>：close/finish 靠 {@code completed} 的 CAS 幂等（回调并发触发仅一方生效）。</p>
 */
public final class TelemetrySpan implements AutoCloseable {

    private final SpanSession backend;
    private final ObservationPipeline pipeline;
    private final String name;
    private final String spanId;
    private final long startMs;
    private Object outputData;
    private boolean rawOutput;
    /** CAS 保证 close/finish 仅一方执行（SSE 的 onCompletion/onTimeout/onError 可能并发触发）。 */
    private final AtomicBoolean completed = new AtomicBoolean(false);

    public TelemetrySpan(String name, SpanSession backend, ObservationPipeline pipeline) {
        this.name = name;
        this.backend = backend;
        this.pipeline = pipeline;
        this.spanId = backend.getSpanId();
        this.startMs = System.currentTimeMillis();
    }

    /** 暴露底层 span 写入器，供入口 step 绑定到会话上下文（trace 级 output 双写）。 */
    public SpanWriter writer() {
        return backend;
    }

    /** 低基数标签（model/channel/eval.*），产 ATTRIBUTE 事件走 pipeline。 */
    public TelemetrySpan tag(String key, Object value) {
        TelemetryEvent event = new TelemetryEvent(TelemetryEvent.EventType.ATTRIBUTE, name, spanId, value);
        event.setIoKey(key);
        pipeline.emit(event, backend);
        return this;
    }

    /** step 输入：产 STEP_INPUT 事件（原始对象，pipeline 内摘要+截断）。 */
    public TelemetrySpan input(Object in) {
        pipeline.emit(new TelemetryEvent(TelemetryEvent.EventType.STEP_INPUT, name, spanId, in), backend);
        return this;
    }

    /** step 输出（摘要经 pipeline，close/finish 时发）。 */
    public TelemetrySpan output(Object out) {
        this.outputData = out;
        this.rawOutput = false;
        return this;
    }

    /** step 输出（原样不走摘要，流式完整 LLM 回答用；仍受截断兜底）。 */
    public TelemetrySpan outputRaw(Object out) {
        this.outputData = out;
        this.rawOutput = true;
        return this;
    }

    /** 写 trace 级 input（原文不摘要），供 root handle 标记 trace IO。 */
    public TelemetrySpan traceInput(Object value) {
        TelemetryEvent event = new TelemetryEvent(TelemetryEvent.EventType.TRACE_IO, name, spanId, value);
        event.setIoKey(AttributeKeys.TRACE_INPUT);
        pipeline.emit(event, backend);
        return this;
    }

    /** 写 trace 级 output。语义同 {@link #traceInput}。 */
    public TelemetrySpan traceOutput(Object value) {
        TelemetryEvent event = new TelemetryEvent(TelemetryEvent.EventType.TRACE_IO, name, spanId, value);
        event.setIoKey(AttributeKeys.TRACE_OUTPUT);
        pipeline.emit(event, backend);
        return this;
    }

    /** 记录异常（委托 backend 各自 recordError）。 */
    public TelemetrySpan error(Throwable t) {
        backend.recordError(t);
        return this;
    }

    /** 同步关闭：emitOutput → closeScope（关 scope + 恢复 MDC 生命周期键）→ end。幂等（CAS）。 */
    @Override
    public void close() {
        if (!completed.compareAndSet(false, true)) {
            return;
        }
        try {
            emitOutput();
        } finally {
            backend.closeScope();
            backend.end();
        }
    }

    /** 异步关闭（流式 doFinally）：emitOutput → end（不关 scope）。幂等（CAS）。 */
    public void finish() {
        if (!completed.compareAndSet(false, true)) {
            return;
        }
        try {
            emitOutput();
        } finally {
            backend.end();
        }
    }

    /** 仅关 scope（SSE：span 延续到 emitter 回调再 finish）。 */
    public void closeScope() {
        backend.closeScope();
    }

    private void emitOutput() {
        TelemetryEvent event = new TelemetryEvent(TelemetryEvent.EventType.STEP_OUTPUT, name, spanId, outputData);
        event.setDurationMs(System.currentTimeMillis() - startMs);
        event.setRaw(rawOutput);
        pipeline.emit(event, backend);
    }
}
