package com.agentframework.crosscutting.trace;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 默认追踪器实现：内存缓冲 + 导出器列表 + 采样策略。
 *
 * <p>不依赖任何外部可观测平台；接入 OpenTelemetry 等实现时只需替换 {@link SpanExporter}。</p>
 */
public final class SimpleTracer implements Tracer {

    private final boolean enabled;
    private final Sampler sampler;
    private final List<SpanExporter> exporters;
    private final List<Span> finished = new ArrayList<>();

    /**
     * @param enabled   是否启用追踪
     * @param sampler   采样器，null 表示全量采样
     * @param exporters 导出器列表
     */
    public SimpleTracer(boolean enabled, Sampler sampler, List<SpanExporter> exporters) {
        this.enabled = enabled;
        this.sampler = sampler == null ? Sampler.alwaysOn() : sampler;
        this.exporters = List.copyOf(exporters == null ? List.of() : exporters);
    }

    /** 构造默认开启、全量采样的追踪器。 */
    public SimpleTracer() {
        this(true, null, null);
    }

    /**
     * @param exporter 导出器
     * @return 仅含该导出器的追踪器
     */
    public static SimpleTracer of(SpanExporter exporter) {
        return new SimpleTracer(true, null, List.of(exporter));
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public TraceContext startTrace(String name, SpanKind kind, Map<String, Object> attributes) {
        return startTrace(null, name, kind, attributes);
    }

    @Override
    public TraceContext startTrace(String traceId, String name, SpanKind kind, Map<String, Object> attributes) {
        if (!enabled || !sampler.shouldSample(name, attributes == null ? Map.of() : attributes)) {
            return new TraceContext(null, null);
        }
        String effectiveTraceId = traceId == null || traceId.isBlank()
                ? UUID.randomUUID().toString().replace("-", "")
                : traceId;
        Span root = new Span(effectiveTraceId, null, name, kind);
        if (attributes != null) {
            attributes.forEach(root::setAttribute);
        }
        return new TraceContext(effectiveTraceId, root);
    }

    @Override
    public Span startSpan(TraceContext trace, Span parent, String name, SpanKind kind,
            Map<String, Object> attributes) {
        if (!enabled || trace == null || !trace.active()) {
            return null;
        }
        Span span = new Span(trace.traceId(), parent == null ? null : parent.id(), name, kind);
        if (attributes != null) {
            attributes.forEach(span::setAttribute);
        }
        return span;
    }

    @Override
    public void endSpan(Span span) {
        finish(span, null);
    }

    @Override
    public void endSpan(Span span, Throwable cause) {
        finish(span, cause);
    }

    @Override
    public void recordEvent(Span span, String name, Map<String, Object> attributes) {
        if (span != null) {
            span.addEvent(new SpanEvent(name, null, attributes));
        }
    }

    @Override
    public void setAttribute(Span span, String key, Object value) {
        if (span != null) {
            span.setAttribute(key, value);
        }
    }

    @Override
    public void flush() {
        List<Span> batch;
        synchronized (finished) {
            batch = new ArrayList<>(finished);
            finished.clear();
        }
        for (Span span : batch) {
            for (SpanExporter exporter : exporters) {
                exporter.export(span);
            }
        }
    }

    @Override
    public List<Span> finishedSpans() {
        synchronized (finished) {
            return List.copyOf(finished);
        }
    }

    /**
     * 结束 span 并放入待导出缓冲。
     *
     * @param span  目标 span，可为 null
     * @param cause 失败原因，null 表示成功
     */
    private void finish(Span span, Throwable cause) {
        if (span == null) {
            return;
        }
        if (cause == null) {
            span.end();
        } else {
            span.endWithError(cause);
        }
        synchronized (finished) {
            finished.add(span);
        }
    }
}
