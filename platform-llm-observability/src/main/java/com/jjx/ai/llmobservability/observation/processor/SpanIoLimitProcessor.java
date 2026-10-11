package com.jjx.ai.llmobservability.observation.processor;

import com.jjx.ai.llmobservability.observation.event.TelemetryEvent;
import com.jjx.ai.llmobservability.observation.support.SpanIoLimits;
import org.springframework.core.annotation.Order;

/**
 * 截断 processor（转·第二环）：把超长 data 截断到 {@link SpanIoLimits#maxSpanIo()}，防撑爆 span attribute。
 *
 * <p><b>所属维度</b>：转（{@link ObservationProcessor} 内置实现，@Order(20)）。</p>
 *
 * <p><b>职责</b>：加工后的 data 若为长字符串，按口径截断——</p>
 * <ul>
 *   <li><b>step IO</b>（STEP_INPUT/STEP_OUTPUT）：substring + 后缀「…(truncated)」。</li>
 *   <li><b>trace IO</b>（TRACE_IO）：纯 substring 无后缀（trace 级要原文，便于 Langfuse 直接看用户问题）。</li>
 * </ul>
 *
 * <p><b>仅对 CharSequence 生效</b>：对象类 data 已被 {@link SummarizeProcessor} 摘要成小对象，
 * 无需截断；非 CharSequence 原样通过。</p>
 *
 * <p><b>不做什么</b>：不写 span（exporter 的事）；不摘要（上一环做）。</p>
 */
@Order(20)
public class SpanIoLimitProcessor implements ObservationProcessor {

    @Override
    public TelemetryEvent process(TelemetryEvent event) {
        if (!SpanIoLimits.isTruncateEnabled()) {
            return event;
        }
        Object data = event.getData();
        if (!(data instanceof CharSequence cs)) {
            return event;  // 非字符串（已摘要的对象），不截断
        }
        String s = cs.toString();
        if (event.getType() == TelemetryEvent.EventType.TRACE_IO) {
            event.setData(s.length() > SpanIoLimits.maxSpanIo() ? s.substring(0, SpanIoLimits.maxSpanIo()) : s);  // trace IO：纯截断无后缀
        } else {
            event.setData(s.length() <= SpanIoLimits.maxSpanIo()
                    ? s : s.substring(0, SpanIoLimits.maxSpanIo()) + "…(truncated)");  // step IO：带后缀
        }
        return event;
    }
}
