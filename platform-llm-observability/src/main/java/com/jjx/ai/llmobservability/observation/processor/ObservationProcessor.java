package com.jjx.ai.llmobservability.observation.processor;

import com.jjx.ai.llmobservability.observation.event.TelemetryEvent;

/**
 * 观测事件的处理器（pipeline 的"转"层）。
 *
 * <p><b>所属维度</b>：转（processor）。对应 otel-collector 的 processor——数据在 pipeline 中串行流过
 * 各 processor，被加工（摘要/截断）或处置（过滤/采样/语义转换）。</p>
 *
 * <p><b>职责</b>：加工或处置一个 {@link TelemetryEvent}。返回加工后的事件（可改 {@code setData} 原地转换），
 * 或返回 <b>null</b> 将其过滤（不再流向 ObservationExporter）。</p>
 *
 * <p><b>扩展点</b>（框架的核心扩展面）：实现本接口 + {@code @Component} + {@code @Order} 即自动进
 * {@link ObservationPipeline} 的 processor 链。内置：摘要（Summarize）、截断（SpanIoLimit）；事件过滤器链（TelemetryFilter）由 ObservationPipeline 包在 processor 链之外；可扩展：采样、
 * 采样、语义映射（如 rag.trace.* → langfuse.*）等。processor 必须无状态（pipeline 全局共享，多线程调用）。</p>
 *
 * <p><b>不做什么</b>：不写 span / 不发日志（那是 ObservationExporter 的职责）；不开/关 span。</p>
 */
public interface ObservationProcessor {

    /**
     * 加工事件。
     *
     * @param event 流入的事件
     * @return 加工后的事件（原对象或新对象）；null 表示过滤掉（不流向 ObservationExporter）
     */
    TelemetryEvent process(TelemetryEvent event);
}
