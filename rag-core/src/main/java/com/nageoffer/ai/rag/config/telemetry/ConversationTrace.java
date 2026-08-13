package com.nageoffer.ai.rag.config.telemetry;

import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一次对话请求的<b>环境级</b> trace 资源：承载 trace 级 input/output（用户问题 / 最终回答）。
 *
 * <p>属性 key 使用与后端无关的 {@code rag.trace.input} / {@code rag.trace.output}：
 * 应用只面向 OTel，任何后端专属语义（如 Langfuse 的 {@code langfuse.observation.*}）由
 * OTel Collector 的 transform processor 在导出前映射，应用零后端知识。
 *
 * <p>构造期捕获 HTTP 根 span 引用（而非每次用 {@link Span#current()}）——对话回答在 Reactor 流式
 * 回调线程产生，{@code Span.current()} 不保证恢复为根 span（与 {@link StepSpan} 同理）。setAttribute
 * 本身线程安全，故 input/output 在任意线程调用都稳。
 *
 * <p>生命周期：input 由 {@link RagTelemetry#startConversation} 在请求入口设置一次；
 * output 由业务在回答完成点（流式 onComplete / 同步分支）显式调用 {@link #output(Object)}。
 * 实例经 {@link ConversationTraceAccessor} 挂在 micrometer context-propagation 里，业务通过
 * {@link RagTelemetry#currentConversationTrace()} 捕获引用后跨线程安全使用。
 *
 * <p>长度上限统一引用 {@link StepSpan#MAX_SPAN_IO}，超长截断（与 span IO 全口径一致）。
 */
public final class ConversationTrace {

    private static final Logger log = LoggerFactory.getLogger(ConversationTrace.class);
    private static final String KEY_INPUT = "rag.trace.input";
    private static final String KEY_OUTPUT = "rag.trace.output";

    private final Span rootSpan;
    /** input 只设一次（首个 user 消息），避免被重复请求/重试覆盖。 */
    private volatile boolean inputSet;

    ConversationTrace(Span rootSpan) {
        this.rootSpan = rootSpan;
    }

    /** 记 trace 级 input（首个 user 消息 = 用户问题）。重复调用忽略。 */
    void input(Object value) {
        if (inputSet || value == null) {
            return;
        }
        inputSet = true;
        set(KEY_INPUT, value);
    }

    /** 记 trace 级 output（assistant 消息 = 回答）。同一请求内多次以最后一次为准。 */
    public void output(Object value) {
        set(KEY_OUTPUT, value);
    }

    private void set(String key, Object value) {
        try {
            if (rootSpan == null) {
                log.warn("[telemetry-diagnose] set {}: rootSpan is null", key);
                return;
            }
            if (!rootSpan.getSpanContext().isValid()) {
                log.warn("[telemetry-diagnose] set {}: rootSpan invalid, spanId={}, traceId={}",
                        key, rootSpan.getSpanContext().getSpanId(), rootSpan.getSpanContext().getTraceId());
                return;
            }
            String s = value.toString();
            rootSpan.setAttribute(key, s.length() > StepSpan.MAX_SPAN_IO
                    ? s.substring(0, StepSpan.MAX_SPAN_IO) : s);
            log.warn("[telemetry-diagnose] set {}: ok, spanId={}, traceId={}, len={}",
                    key, rootSpan.getSpanContext().getSpanId(), rootSpan.getSpanContext().getTraceId(), s.length());
        } catch (Throwable e) {
            // span 已结束或设置失败，忽略（不阻断业务）
            log.warn("[telemetry-diagnose] set {}: exception {}", key, e.toString());
        }
    }
}
