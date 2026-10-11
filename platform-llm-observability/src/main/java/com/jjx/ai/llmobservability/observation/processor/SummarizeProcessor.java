package com.jjx.ai.llmobservability.observation.processor;

import com.jjx.ai.llmobservability.observation.event.TelemetryEvent;
import com.jjx.ai.llmobservability.observation.support.Summarizer;
import org.springframework.core.annotation.Order;

/**
 * 摘要 processor（转·第一环）：把 STEP_INPUT/STEP_OUTPUT 的大 data 摘要成小对象。
 *
 * <p><b>所属维度</b>：转（{@link ObservationProcessor} 内置实现，@Order(10) 链首）。</p>
 *
 * <p><b>职责</b>：对 STEP_INPUT / STEP_OUTPUT 的 data 调 {@link Summarizer#summarize}（字段展开、
 * 深层集合记 size+preview、字符串截断），防大 payload（完整检索结果/整篇文档）膨胀 span/日志。</p>
 *
 * <p><b>raw 语义</b>：{@code event.raw=true}（流式完整 LLM 回答，outputRaw 设置）时跳过摘要——
 * 完整回答要原文落 OpenObserve Output 面板；后续 {@link SpanIoLimitProcessor} 仍做 maxSpanIo 截断兜底。</p>
 *
 * <p><b>不做什么</b>：不处理 TRACE_IO（trace 级 IO 要原文，不摘要）；不截断（下一环 SpanIoLimit 做）。</p>
 */
@Order(10)
public class SummarizeProcessor implements ObservationProcessor {

    @Override
    public TelemetryEvent process(TelemetryEvent event) {
        if (!Summarizer.isSummarizeEnabled()) {
            return event;
        }
        if (event.isRaw()) {
            return event;  // 原样输出（流式完整回答），跳过摘要
        }
        switch (event.getType()) {
            case STEP_INPUT, STEP_OUTPUT -> event.setData(Summarizer.summarize(event.getData()));
            default -> { /* TRACE_IO/ATTRIBUTE/CUSTOM 不摘要 */ }
        }
        return event;
    }
}
