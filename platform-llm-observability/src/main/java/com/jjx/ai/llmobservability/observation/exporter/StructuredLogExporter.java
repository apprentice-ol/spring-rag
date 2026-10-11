package com.jjx.ai.llmobservability.observation.exporter;

import com.jjx.ai.llmobservability.observation.logging.TelemetryStructuredLog;
import com.jjx.ai.llmobservability.observation.span.SpanWriter;
import com.jjx.ai.llmobservability.observation.event.TelemetryEvent;
import org.springframework.core.annotation.Order;

/**
 * 发结构化日志的 exporter（发·出口 B）。
 *
 * <p><b>所属维度</b>：发（{@link ObservationExporter} 内置实现，@Order(20)）。</p>
 *
 * <p><b>职责</b>：STEP_INPUT → {@code step.input} 事件；STEP_OUTPUT → {@code step.output}（含 duration_ms）；
 * CUSTOM → 原样转发 {@link TelemetryStructuredLog#emit(String, Object)}（llm.request 等，step/stepId 取自 MDC）。
 * 经 slf4j 发出，落哪个后端由应用 logback 配置决定（后端中立）。</p>
 *
 * <p><b>不做什么</b>：不写 span attribute（{@link SpanAttributeExporter}）；TRACE_IO/ATTRIBUTE 不发日志。</p>
 */
@Order(20)
public class StructuredLogExporter implements ObservationExporter {

    @Override
    public void export(TelemetryEvent event, SpanWriter target) {
        switch (event.getType()) {
            case STEP_INPUT -> TelemetryStructuredLog.emitUnfiltered("step.input", event.getName(), event.getSpanId(), event.getData(), null);
            case STEP_OUTPUT -> TelemetryStructuredLog.emitUnfiltered("step.output", event.getName(), event.getSpanId(), event.getData(), event.getDurationMs());
            case CUSTOM -> TelemetryStructuredLog.emitUnfiltered(event.getName(), event.getData());
            default -> { /* TRACE_IO/ATTRIBUTE：不发日志 */ }
        }
    }
}
