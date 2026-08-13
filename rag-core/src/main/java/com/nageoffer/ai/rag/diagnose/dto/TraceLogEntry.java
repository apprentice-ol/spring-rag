package com.nageoffer.ai.rag.diagnose.dto;

/**
 * OpenObserve 日志条目（按 traceid 关联）。
 *
 * <p>字段对应 appender 写入结构（OpenObserve 字段名全小写）：
 * timestamp/level/logger/thread/message/traceid/spanid，异常时额外有 exception/exceptionClass。
 */
public record TraceLogEntry(
        long timestamp,         // 应用层时间戳（微秒，event.getTimeStamp()*1000）
        String level,           // TRACE/DEBUG/INFO/WARN/ERROR
        String logger,
        String thread,
        String message,
        String traceId,
        String spanId,
        String exceptionClass,  // 有异常时非空
        String exception        // 堆栈全文，有异常时非空
) {
}
