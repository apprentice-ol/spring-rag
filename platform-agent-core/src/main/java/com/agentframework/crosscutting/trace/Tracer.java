package com.agentframework.crosscutting.trace;

import java.util.List;
import java.util.Map;

/**
 * 追踪器：创建 span、记录事件并导出。
 *
 * <p>未命中采样时所有方法都应安全退化为空实现，调用方无需判空。</p>
 */
public interface Tracer {

    /** @return 当前是否启用追踪 */
    boolean enabled();

    /**
     * 开启一条链路。
     *
     * @param name       链路名称
     * @param kind       根 span 类型
     * @param attributes 链路属性
     * @return 链路上下文；未采样时返回非激活上下文
     */
    TraceContext startTrace(String name, SpanKind kind, Map<String, Object> attributes);

    /**
     * 用指定 traceId 开启链路，使链路与会话的 {@code traceId} 对齐。
     *
     * <p>默认实现忽略传入的 traceId，交给实现自行生成；支持外部 traceId 的实现应覆盖本方法。</p>
     *
     * @param traceId    期望的链路 id，空白表示交由实现生成
     * @param name       链路名称
     * @param kind       根 span 类型
     * @param attributes 链路属性
     * @return 链路上下文
     */
    default TraceContext startTrace(String traceId, String name, SpanKind kind, Map<String, Object> attributes) {
        return startTrace(name, kind, attributes);
    }

    /**
     * 开启子 span。
     *
     * @param trace      链路上下文
     * @param parent     父 span，可为 null
     * @param name       span 名称
     * @param kind       span 类型
     * @param attributes 属性
     * @return 新 span；未采样时返回 null
     */
    Span startSpan(TraceContext trace, Span parent, String name, SpanKind kind, Map<String, Object> attributes);

    /**
     * 以成功状态结束 span。
     *
     * @param span 目标 span
     */
    void endSpan(Span span);

    /**
     * 以失败状态结束 span。
     *
     * @param span  目标 span
     * @param cause 失败原因
     */
    void endSpan(Span span, Throwable cause);

    /**
     * 记录 span 事件。
     *
     * @param span       目标 span
     * @param name       事件名
     * @param attributes 事件属性
     */
    void recordEvent(Span span, String name, Map<String, Object> attributes);

    /**
     * 写入 span 属性。
     *
     * @param span  目标 span
     * @param key   属性名
     * @param value 属性值
     */
    void setAttribute(Span span, String key, Object value);

    /**
     * 导出全部已结束的 span 并清空缓冲。
     */
    void flush();

    /** @return 已结束但尚未导出的 span 列表 */
    List<Span> finishedSpans();
}
