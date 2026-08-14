package com.nageoffer.ai.obs.event;

import com.nageoffer.ai.obs.backend.SpanBackend;
import com.nageoffer.ai.obs.processor.SpanIoLimits;
import com.nageoffer.ai.obs.processor.TracePipeline;

/**
 * 一个观测步骤 / 根的 trace 句柄：封装"开→输入→输出→异常→关闭"的生命周期模板。
 *
 * <p><b>所属维度</b>：②event（Source 与 pipeline 之间的句柄）。固化步骤生命周期骨架，把"底层 span 怎么开/关"
 * 委托给 {@link SpanBackend}（策略），把"事件怎么转/发"委托给 {@link TracePipeline}（接→转→发）。
 * <b>不做任何数据加工</b>——input/output 只产 {@link TraceEvent} 丢进 pipeline，摘要/截断由 processor 链做，
 * 落地由 exporter 链做。</p>
 *
 * <p><b>职责</b>：暴露 {@code tag/input/output/outputRaw/traceInput/traceOutput/error} 链式 API；
 * 两种关闭语义 {@link #close()}（同步）/ {@link #finish()}（流式）。</p>
 *
 * <p><b>协作</b>：由 {@code Telemetry.openStep/openTrace} 构造；被业务与 {@code source.TraceStepAspect} 使用。</p>
 *
 * <p><b>不做什么</b>：不加工 data（processor 的事）；不写 span/发日志（exporter 的事）；不碰 MDC（backend.closeScope 管）。</p>
 *
 * <p><b>线程安全</b>：close/finish 靠 {@code completed} 标志幂等。</p>
 */
public final class TraceHandle implements AutoCloseable {

    private final SpanBackend backend;
    private final TracePipeline pipeline;
    private final String name;
    private final String spanId;
    private final long startMs;
    private Object outputData;
    private boolean rawOutput;
    private volatile boolean completed;

    public TraceHandle(String name, SpanBackend backend, TracePipeline pipeline) {
        this.name = name;
        this.backend = backend;
        this.pipeline = pipeline;
        this.spanId = backend.getSpanId();
        this.startMs = System.currentTimeMillis();
    }

    /** 低基数标签（model/channel/eval.*），产 ATTRIBUTE 事件走 pipeline。 */
    public TraceHandle tag(String key, Object value) {
        TraceEvent event = new TraceEvent(TraceEvent.EventType.ATTRIBUTE, name, spanId, value);
        event.setIoKey(key);
        pipeline.emit(event, backend);
        return this;
    }

    /** step 输入：产 STEP_INPUT 事件（原始对象，pipeline 内摘要+截断）。 */
    public TraceHandle input(Object in) {
        pipeline.emit(new TraceEvent(TraceEvent.EventType.STEP_INPUT, name, spanId, in), backend);
        return this;
    }

    /** step 输出（摘要经 pipeline，close/finish 时发）。 */
    public TraceHandle output(Object out) {
        this.outputData = out;
        this.rawOutput = false;
        return this;
    }

    /** step 输出（原样不走摘要，流式完整 LLM 回答用；仍受截断兜底）。 */
    public TraceHandle outputRaw(Object out) {
        this.outputData = out;
        this.rawOutput = true;
        return this;
    }

    /** 写 trace 级 input（原文不摘要），供 root handle 标记 trace IO。 */
    public TraceHandle traceInput(Object value) {
        TraceEvent event = new TraceEvent(TraceEvent.EventType.TRACE_IO, name, spanId, value);
        event.setIoKey(SpanIoLimits.KEY_TRACE_INPUT);
        pipeline.emit(event, backend);
        return this;
    }

    /** 写 trace 级 output。语义同 {@link #traceInput}。 */
    public TraceHandle traceOutput(Object value) {
        TraceEvent event = new TraceEvent(TraceEvent.EventType.TRACE_IO, name, spanId, value);
        event.setIoKey(SpanIoLimits.KEY_TRACE_OUTPUT);
        pipeline.emit(event, backend);
        return this;
    }

    /** 记录异常（委托 backend 各自 recordError）。 */
    public TraceHandle error(Throwable t) {
        backend.recordError(t);
        return this;
    }

    /** 同步关闭：emitOutput → closeScope（关 scope + 清 MDC 生命周期键）→ end。幂等。 */
    @Override
    public void close() {
        if (completed) {
            return;
        }
        completed = true;
        try {
            emitOutput();
        } finally {
            backend.closeScope();
            backend.end();
        }
    }

    /** 异步关闭（流式 doFinally）：emitOutput → end（不关 scope）。幂等。 */
    public void finish() {
        if (completed) {
            return;
        }
        completed = true;
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
        TraceEvent event = new TraceEvent(TraceEvent.EventType.STEP_OUTPUT, name, spanId, outputData);
        event.setDurationMs(System.currentTimeMillis() - startMs);
        event.setRaw(rawOutput);
        pipeline.emit(event, backend);
    }
}
