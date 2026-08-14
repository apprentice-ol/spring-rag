package com.nageoffer.ai.obs.exporter;

import com.nageoffer.ai.obs.StructuredLog;
import com.nageoffer.ai.obs.backend.SpanWriter;
import com.nageoffer.ai.obs.event.TraceEvent;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 发结构化日志的 exporter（发·出口 B）。
 *
 * <p><b>所属维度</b>：发（{@link TraceExporter} 内置实现，@Order(20)）。</p>
 *
 * <p><b>职责</b>：STEP_INPUT → {@code step.input} 事件；STEP_OUTPUT → {@code step.output}（含 duration_ms）；
 * CUSTOM → 原样转发 {@link StructuredLog#emit(String, Object)}（llm.request 等，step/stepId 取自 MDC）。
 * 经 slf4j 发出，落哪个后端由应用 logback 配置决定（后端中立）。</p>
 *
 * <p><b>不做什么</b>：不写 span attribute（{@link SpanAttributeExporter}）；TRACE_IO/ATTRIBUTE 不发日志。</p>
 */
@Component
@Order(20)
public class StructuredLogExporter implements TraceExporter {

    @Override
    public void export(TraceEvent event, SpanWriter target) {
        switch (event.getType()) {
            case STEP_INPUT -> StructuredLog.emit("step.input", event.getName(), event.getSpanId(), event.getData(), null);
            case STEP_OUTPUT -> StructuredLog.emit("step.output", event.getName(), event.getSpanId(), event.getData(), event.getDurationMs());
            case CUSTOM -> StructuredLog.emit(event.getName(), event.getData());
            default -> { /* TRACE_IO/ATTRIBUTE：不发日志 */ }
        }
    }
}
