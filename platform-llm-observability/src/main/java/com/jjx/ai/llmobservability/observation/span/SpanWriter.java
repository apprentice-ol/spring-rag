package com.jjx.ai.llmobservability.observation.span;

/**
 * 写 span 的最小能力接口。sink 责任链<b>唯一</b>依赖的后端能力，不感知底层是 Observation、OTel 直开还是 ambient 当前 span。
 *
 * <p><b>所属维度</b>：②backend（策略）。sink 层与 backend 层之间的窄接口（依赖倒置）。</p>
 *
 * <p><b>职责</b>：写高基数值 / 写低基数标签 / 记异常。</p>
 *
 * <p><b>协作</b>：{@link SpanSession} 继承它；{@code sink.SinkChain} 把它作为回调参数传给各 sink；{@link #current()} 给同步请求线程的 ambient 写入。</p>
 *
 * <p><b>不做什么</b>：不管 span 生命周期，不碰 MDC。</p>
 *
 * <p><b>线程安全</b>：实现持有构造期捕获的 span 引用（非 {@code Span.current()}），跨线程写属性稳定。</p>
 */
public interface SpanWriter {

    /** 写高基数字符串属性（input/output/rag.trace.*，值应由调用方按 {@code SpanIoLimits.maxSpanIo()} 截断）。 */
    void setAttribute(String key, String value);

    /** 写低基数标签（model/channel/hits 等可枚举值，可聚合）。null 由实现兜底为空串。 */
    void setTag(String key, String value);

    /** 记录异常（observation.error / span.recordException + ERROR 状态）。 */
    void recordError(Throwable t);

    /** 写当前 ambient span（{@code Span.current()}）的写入器单例。供同步请求线程的 ambient 系列方法与 SSE 回调用。 */
    static SpanWriter current() {
        return CurrentSpan.INSTANCE;
    }
}
