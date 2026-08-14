package com.nageoffer.ai.obs.exporter;

import com.nageoffer.ai.obs.backend.SpanWriter;
import com.nageoffer.ai.obs.event.TraceEvent;

/**
 * 观测事件的导出器（pipeline 的"发"层）。
 *
 * <p><b>所属维度</b>：发（exporter）。对应 otel-collector 的 exporter——事件经 processor 链加工后，
 * fan-out 给所有 exporter 落地。</p>
 *
 * <p><b>职责</b>：把加工后的 {@link TraceEvent} 落到一个具体出口。{@code target} 是本次事件的写 span 目标
 * （TraceHandle 持有的 backend / 对话根 span / 当前 ambient span），各 exporter 按事件类型选取自己关心的
 * 属性写。</p>
 *
 * <p><b>扩展点</b>：实现本接口 + {@code @Component} + {@code @Order} 即自动进 {@code TracePipeline} 的
 * exporter 链。内置：写 span attribute / 发结构化日志 / metrics（预留）；可扩展：直推 Langfuse SDK、
 * 发 Kafka 等。exporter 必须无状态；不应再加工 data（那是 processor 的职责）。</p>
 *
 * <p><b>不做什么</b>：不加工数据（data 已被 processor 摘要/截断，直接落）；不感知 span 生命周期。</p>
 */
public interface TraceExporter {

    /**
     * 落地一个事件。
     *
     * @param event  加工后的事件（data 已可直写）
     * @param target 本次事件的写 span 目标
     */
    void export(TraceEvent event, SpanWriter target);
}
