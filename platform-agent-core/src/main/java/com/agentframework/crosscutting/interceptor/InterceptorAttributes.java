package com.agentframework.crosscutting.interceptor;

/**
 * 拦截器上下文的标准属性键。
 *
 * <p>用常量而非魔法字符串，保证引擎与拦截器之间的契约稳定。</p>
 */
public final class InterceptorAttributes {

    /** 缓存策略，值类型 {@code CachePolicy}。 */
    public static final String CACHE_POLICY = "cache.policy";
    /** 缓存键所用载荷，任意对象。 */
    public static final String CACHE_INPUT = "cache.input";
    /** 缓存命名空间，通常为会话 id。 */
    public static final String CACHE_NAMESPACE = "cache.namespace";
    /** 缓存操作名。 */
    public static final String CACHE_OPERATION = "cache.operation";

    /** 重试策略，值类型 {@code RetryPolicy}。 */
    public static final String RETRY_POLICY = "retry.policy";
    /** 超时策略，值类型 {@code TimeoutPolicy}。 */
    public static final String TIMEOUT_POLICY = "timeout.policy";
    /** 熔断分组键。 */
    public static final String CIRCUIT_KEY = "circuit.key";

    /** 链路上下文，值类型 {@code TraceContext}。 */
    public static final String TRACE = "trace.context";
    /** 父 span，值类型 {@code Span}。 */
    public static final String TRACE_PARENT = "trace.parent";
    /** span 名称。 */
    public static final String SPAN_NAME = "trace.span.name";
    /** span 类型，值类型 {@code SpanKind}。 */
    public static final String SPAN_KIND = "trace.span.kind";

    /** Agent id。 */
    public static final String AGENT_ID = "agent.id";
    /** 节点 id。 */
    public static final String NODE_ID = "node.id";
    /** 工具 id。 */
    public static final String TOOL_ID = "tool.id";
    /** 模型名。 */
    public static final String MODEL = "model.name";

    private InterceptorAttributes() {
    }
}
