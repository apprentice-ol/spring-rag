package com.agentframework.crosscutting.trace;

/** Span 类型：标记这段耗时代表执行链上的哪一环。 */
public enum SpanKind {
    AGENT,
    WORKFLOW,
    NODE,
    PROMPT,
    LLM,
    TOOL,
    CACHE,
    INTERNAL
}
