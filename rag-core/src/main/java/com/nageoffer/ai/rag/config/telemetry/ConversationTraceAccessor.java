package com.nageoffer.ai.rag.config.telemetry;

import io.micrometer.context.ThreadLocalAccessor;

/**
 * 把 {@link ConversationTrace} ambient 作用域接入 micrometer context-propagation，
 * 使 {@code ContextSnapshot.captureAll()}（虚拟线程）与 {@code Hooks.enableAutomaticContextPropagation}
 * （Reactor 回调）能把它跨线程透传——从而 StreamChatPipeline / saveMessage 在任意线程都能取到当前对话
 * 的 trace，无需参数传递。
 *
 * <p>与 {@link OpenTelemetryContextThreadLocalAccessor}、{@code Slf4jThreadLocalAccessor} 并存注册于
 * {@link ContextPropagationConfig}，三者各自管一种 ThreadLocal（OTel Context / MDC / ConversationTrace）。
 *
 * <p>{@code setValue()}（无参）必须覆写——否则 {@code ThreadLocalAccessor} 默认链路
 * {@code restore()→setValue(V)→reset()} 会抛 IllegalStateException（见 {@link OpenTelemetryContextThreadLocalAccessor} 注释）。
 */
public final class ConversationTraceAccessor implements ThreadLocalAccessor<ConversationTrace> {

    /** ContextRegistry 内以 key 去重，全局唯一。 */
    public static final String KEY = "rag.conversation";

    /** ambient 持有者；{@link RagTelemetry} 直接读写（同包），传播由 accessor 接管。 */
    static final ThreadLocal<ConversationTrace> HOLDER = new ThreadLocal<>();

    @Override
    public Object key() {
        return KEY;
    }

    @Override
    public ConversationTrace getValue() {
        return HOLDER.get();
    }

    @Override
    public void setValue(ConversationTrace value) {
        HOLDER.set(value);
    }

    /** 清空（回退到无 trace）——线程结束/作用域退出时调用，防默认链路抛 IllegalStateException。 */
    @Override
    public void setValue() {
        HOLDER.remove();
    }
}
