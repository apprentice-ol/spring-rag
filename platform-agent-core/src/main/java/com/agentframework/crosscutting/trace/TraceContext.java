package com.agentframework.crosscutting.trace;

/**
 * 链路上下文：一次会话级执行共享的 traceId 与根 span。
 *
 * @param traceId  链路 id
 * @param rootSpan 根 span
 */
public record TraceContext(String traceId, Span rootSpan) {

    /**
     * @return 当前上下文是否可用（未采样时 traceId 为 null）
     */
    public boolean active() {
        return traceId != null;
    }
}
