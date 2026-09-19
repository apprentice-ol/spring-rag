package com.agentframework.crosscutting.trace;

/**
 * Span 导出器扩展点：把结束的 span 写入日志、存储或外部可观测平台。
 */
@FunctionalInterface
public interface SpanExporter {

    /**
     * 导出单个 span。
     *
     * @param span 已结束的 span
     */
    void export(Span span);
}
