package com.nageoffer.ai.rag.config.telemetry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 统一日志入口：兼容 slf4j 习惯（{@code log.info/debug/warn/error}）+ 附属结构化事件
 *（{@code log.event}）。
 *
 * <p><b>步骤埋点已注解化</b>：业务方法加 {@link TraceStep} 注解即可（由 {@link TraceStepAspect} 自动开/关 span），
 * 无需再手写 {@code log.step/log.stream}。仅 AOP 盲区（静态方法 / 私有方法）才直接用 {@link RagTelemetry}。</p>
 *
 * <p>用法（每个业务类持有一个 static final log，和 {@code @Slf4j} 一样）：
 * <pre>{@code
 * private static final RagLogger log = RagLogger.of(MyClass.class);
 *
 * log.info("[模块] xxx={}", v);           // 普通日志（自动带 traceId/spanId）
 * log.event("llm.request", data);         // 附属结构化事件（step/stepId 自动从 MDC 取）
 * }</pre>
 */
public final class RagLogger {

    private final Logger slf4j;

    /** 每个业务类创建自己的 log（per-class slf4j Logger）。 */
    public static RagLogger of(Class<?> clazz) {
        return new RagLogger(LoggerFactory.getLogger(clazz));
    }

    private RagLogger(Logger slf4j) {
        this.slf4j = slf4j;
    }

    // ===== slf4j 委托（普通日志，自动带 MDC 的 traceId/spanId）=====
    public void info(String format, Object... args) { slf4j.info(format, args); }
    public void debug(String format, Object... args) { slf4j.debug(format, args); }
    public void warn(String format, Object... args) { slf4j.warn(format, args); }
    public void error(String format, Object... args) { slf4j.error(format, args); }
    public void error(String format, Throwable t) { slf4j.error(format, t); }
    public boolean isDebugEnabled() { return slf4j.isDebugEnabled(); }
    public boolean isInfoEnabled() { return slf4j.isInfoEnabled(); }

    /** 发一条附属结构化事件（llm.request / rerank.scores 等），step/stepId 自动从当前 MDC 取，免去手写。 */
    public void event(String event, Object data) {
        StructuredLog.emit(event, data);
    }

    public ConversationTrace currentConversationTrace() {
        return RagTelemetry.getInstance().currentConversationTrace();
    }
}
