package com.jjx.ai.llmobservability.observation.filter;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;
import com.jjx.ai.llmobservability.observation.event.TelemetryEvent;
import com.jjx.ai.llmobservability.observation.logging.TelemetryStructuredLog;

/**
 * logback appender 前的统一过滤器：把普通日志（含框架/第三方日志）也纳入同一过滤器链。
 *
 * <p><b>所属维度</b>：发（日志生命周期最外层——所有日志离开应用前最后一道闸）。</p>
 *
 * <p><b>模型</b>：每条日志的格式化消息被包装为 {@code CUSTOM} 事件（name={@code log.<level>}，
 * data=消息文本），走同一 {@link TelemetryFilterChain}；过滤器放行则 {@code NEUTRAL}，
 * 丢弃则 {@code DENY}（该日志不会到达任何 appender / OTLP Logs）。</p>
 *
 * <p><b>不重复过滤</b>：结构化日志（logger={@link TelemetryStructuredLog#LOGGER_NAME}）已在
 * 事件管线或直发入口过滤过，这里直接放行，避免同一事件被过滤器执行两次。</p>
 */
public class TelemetryLogbackFilter extends Filter<ILoggingEvent> {

    private final TelemetryFilterChain chain;

    public TelemetryLogbackFilter(TelemetryFilterChain chain) {
        this.chain = chain;
    }

    @Override
    public FilterReply decide(ILoggingEvent event) {
        if (TelemetryStructuredLog.LOGGER_NAME.equals(event.getLoggerName())) {
            return FilterReply.NEUTRAL;
        }
        TelemetryEvent telemetryEvent = new TelemetryEvent(
                TelemetryEvent.EventType.CUSTOM,
                "log." + event.getLevel().toString().toLowerCase(),
                null,
                event.getFormattedMessage());
        return chain.apply(telemetryEvent) ? FilterReply.NEUTRAL : FilterReply.DENY;
    }
}
