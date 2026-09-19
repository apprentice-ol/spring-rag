package com.agentframework.crosscutting.interceptor;

/** 拦截器挂载点：控制管道在哪些执行单元外层生效。 */
public enum InterceptorPhase {
    AROUND_AGENT,
    AROUND_WORKFLOW,
    AROUND_NODE,
    AROUND_PROMPT,
    AROUND_LLM,
    AROUND_TOOL
}
