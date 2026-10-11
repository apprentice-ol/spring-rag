package com.jjx.ai.llmobservability.observation.event;

import lombok.Data;

/**
 * 观测事件的统一数据模型——pipeline（接→转→发）中流转的载体。
 *
 * <p><b>所属维度</b>：②event（数据模型）。Source 产它，ObservationProcessor 链加工它，ObservationExporter 链落它。</p>
 *
 * <p><b>职责</b>：承载一次观测事件的全部信息。{@link #data} 是核心载荷——进入 pipeline 时可能是原始对象，
 * 经 ObservationProcessor 链逐级加工（摘要→截断→[过滤/转换]），到 ObservationExporter 时已是可直写的形态。</p>
 *
 * <p><b>生命周期</b>：事件在 pipeline 中串行流转；任一 ObservationProcessor 返回 null 即被过滤，不再到 ObservationExporter。
 * ObservationProcessor 可原地修改 data（{@link #setData}）实现丰富/转换。</p>
 */
@Data
public class TelemetryEvent {

    /** 事件类型。 */
    private final EventType type;

    /** step 名 / 事件名（如 rag.chat / llm.request）。 */
    private final String name;

    /** 关联 spanId（MDC step_id，日志关联用）。 */
    private final String spanId;

    /** 核心载荷：原始对象 → processor 加工 → exporter 直写。 */
    private Object data;

    /** 仅 STEP_OUTPUT：步骤耗时 ms。 */
    private Long durationMs;

    /** 仅 TRACE_IO / ATTRIBUTE：attribute key（gen_ai.input/output.messages / model 等）。 */
    private String ioKey;

    /** true = data 是原样输出（流式完整 LLM 回答），SummarizeProcessor 跳过摘要（仍受截断兜底）。 */
    private boolean raw;

    public TelemetryEvent(EventType type, String name, String spanId, Object data) {
        this.type = type;
        this.name = name;
        this.spanId = spanId;
        this.data = data;
    }

    /** 事件类型（与旧 sink 的 5 回调一一对应，统一为单一模型）。 */
    public enum EventType {
        /** step 输入。 */
        STEP_INPUT,
        /** step 输出（含 durationMs）。 */
        STEP_OUTPUT,
        /** trace 级 IO（gen_ai.input/output.messages，原文不摘要）。 */
        TRACE_IO,
        /** 低基数标签（model/channel 等）。 */
        ATTRIBUTE,
        /** 自定义事件（llm.request / rerank.scores 等）。 */
        CUSTOM
    }
}
