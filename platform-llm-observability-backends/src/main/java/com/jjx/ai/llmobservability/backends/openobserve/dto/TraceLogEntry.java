package com.jjx.ai.llmobservability.backends.openobserve.dto;

/**
 * OpenObserve 日志条目（按 traceId 关联）。
 *
 * <p>字段对应应用 OTLP 日志写入结构（OpenObserve 字段名全小写）：
 * timestamp/level/logger/thread/message/trace_id/span_id，异常时额外有 exception 相关字段。</p>
 *
 * @param timestamp       应用层时间戳（微秒）
 * @param level           日志级别（TRACE/DEBUG/INFO/WARN/ERROR）
 * @param logger          日志器名
 * @param thread          线程名
 * @param message         日志正文
 * @param traceId         关联的 trace id
 * @param spanId          关联的 span id
 * @param exceptionClass  异常类名；无异常时为 null
 * @param exception       异常堆栈全文；无异常时为 null
 */
public record TraceLogEntry(
        long timestamp,
        String level,
        String logger,
        String thread,
        String message,
        String traceId,
        String spanId,
        String exceptionClass,
        String exception
) {
}
