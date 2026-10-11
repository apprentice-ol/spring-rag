package com.jjx.ai.llmobservability.observation.logging;

import java.util.function.Consumer;
import com.jjx.ai.llmobservability.observation.TelemetryTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 统一日志门面：兼容 slf4j 习惯（{@code info/debug/warn/error}）+ 附属结构化事件（{@code event}）+ 对话级 output（{@code conversationOutput}）。
 *
 * <p><b>所属维度</b>：门面（Facade）。业务持有一个 {@code static final} 实例（和 {@code @Slf4j} 一样），只通过它接触观测，不感知底层。</p>
 *
 * <p><b>职责</b>：slf4j 委托（自动带 MDC traceId/spanId）；{@link #event}（经 {@link TelemetryTemplate#emit} → sink 链）；{@link #conversationOutput}/{@link #conversationSink}（对话级 trace output）。</p>
 *
 * <p><b>协作</b>：通过 {@link TelemetryTemplate#getInstance()} 静态委托（Spring 启动后非空）。</p>
 *
 * <p><b>用法</b>：
 * <pre>{@code
 * private static final TelemetryLogger log = TelemetryLogger.of(MyClass.class);
 * log.info("[模块] xxx={}", v);
 * log.event("llm.request", data);
 * log.conversationOutput(answer);
 * }</pre>
 *
 * <p><b>不做什么</b>：不直接接触 {@code ConversationContext} 类型（已收口进 {@code conversationOutput}）；不感知 span 生命周期。</p>
 */
public final class TelemetryLogger {

    private final Logger slf4j;

    public static TelemetryLogger of(Class<?> clazz) {
        return new TelemetryLogger(LoggerFactory.getLogger(clazz));
    }

    private TelemetryLogger(Logger slf4j) {
        this.slf4j = slf4j;
    }

    public void info(String format, Object... args) { slf4j.info(format, args); }
    public void debug(String format, Object... args) { slf4j.debug(format, args); }
    public void warn(String format, Object... args) { slf4j.warn(format, args); }
    public void error(String format, Object... args) { slf4j.error(format, args); }
    public void error(String format, Throwable t) { slf4j.error(format, t); }
    public boolean isDebugEnabled() { return slf4j.isDebugEnabled(); }
    public boolean isInfoEnabled() { return slf4j.isInfoEnabled(); }

    /** 发一条附属结构化事件，经 sink 链落地。step/stepId 自动从当前 MDC 取。 */
    public void event(String event, Object data) {
        TelemetryTemplate.getInstance().emit(event, data);
    }

    /** 写对话级 output（依赖回调线程 ambient 传播恢复；流式回调场景应改用 {@link #conversationSink()} 捕获引用）。 */
    public void conversationOutput(Object value) {
        TelemetryTemplate.getInstance().conversationOutput(value);
    }

    /** 捕获当前对话 output 写入器，供流式回调跨线程写 conversation output（subscribe 前于业务线程捕获，回调里 accept）。 */
    public Consumer<Object> conversationSink() {
        return TelemetryTemplate.getInstance().conversationSink();
    }
}
