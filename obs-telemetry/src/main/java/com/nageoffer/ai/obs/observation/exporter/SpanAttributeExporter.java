package com.nageoffer.ai.obs.observation.exporter;

import com.nageoffer.ai.obs.observation.span.SpanWriter;
import com.nageoffer.ai.obs.observation.event.ObsEvent;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 写 span attribute 的 exporter（发·出口 A）。
 *
 * <p><b>所属维度</b>：发（{@link ObservationExporter} 内置实现，@Order(10)）。</p>
 *
 * <p><b>职责</b>：把事件写为 span attribute（data 已被 processor 摘要/截断，直接落）：</p>
 * <ul>
 *   <li>STEP_INPUT → {@code input}；STEP_OUTPUT → {@code output}（OpenObserve trace Input/Output 面板读）。</li>
 *   <li>TRACE_IO → {@code event.ioKey}（rag.trace.input/output，Collector 映射 langfuse.observation.*）。</li>
 *   <li>ATTRIBUTE → {@code target.setTag}（低基数标签，可聚合）。</li>
 * </ul>
 *
 * <p><b>不做什么</b>：不加工 data（processor 已做）；CUSTOM 不写 attribute（StructuredLogExporter 发日志）。</p>
 */
@Component
@Order(10)
public class SpanAttributeExporter implements ObservationExporter {

    @Override
    public void export(ObsEvent event, SpanWriter target) {
        switch (event.getType()) {
            case STEP_INPUT -> target.setAttribute("input", stringify(event.getData()));
            case STEP_OUTPUT -> target.setAttribute("output", stringify(event.getData()));
            case TRACE_IO -> {
                if (event.getData() != null) {
                    target.setAttribute(event.getIoKey(), stringify(event.getData()));
                }
            }
            case ATTRIBUTE -> target.setTag(event.getIoKey(),
                    event.getData() == null ? "" : event.getData().toString());
            default -> { /* CUSTOM：不写 attribute */ }
        }
    }

    /** CharSequence 原样；其余 toString（data 已是小对象）。 */
    private String stringify(Object data) {
        return data instanceof CharSequence cs ? cs.toString() : String.valueOf(data);
    }
}
