package com.nageoffer.ai.obs.propagation;

import com.nageoffer.ai.obs.event.ConversationContext;
import io.micrometer.context.ThreadLocalAccessor;

/**
 * 把 {@link ConversationContext} ambient 作用域接入 micrometer context-propagation。
 *
 * <p><b>所属维度</b>：④传播。</p>
 *
 * <p><b>职责</b>：使 {@code ContextSnapshot.captureAll()}（虚拟线程）与 {@code Hooks.enableAutomaticContextPropagation}
 * （Reactor 回调）能把当前对话上下文跨线程透传——业务在任意线程都能取到当前对话 trace 写 output，无需参数传递。</p>
 *
 * <p><b>协作</b>：注册于 {@link ContextPropagationConfig}；{@code HOLDER} 由 {@code Telemetry} 直接读写
 * （beginConversation 设 / conversationOutput 读 / ConversationAdvisor 退出时 remove）。</p>
 *
 * <p><b>{@code setValue()}（无参）必须覆写</b>——否则 {@link ThreadLocalAccessor} 默认链路抛 IllegalStateException。</p>
 */
public final class ConversationContextAccessor implements ThreadLocalAccessor<ConversationContext> {

    public static final String KEY = "obs.conversation";

    public static final ThreadLocal<ConversationContext> HOLDER = new ThreadLocal<>();

    @Override
    public Object key() {
        return KEY;
    }

    @Override
    public ConversationContext getValue() {
        return HOLDER.get();
    }

    @Override
    public void setValue(ConversationContext value) {
        HOLDER.set(value);
    }

    @Override
    public void setValue() {
        HOLDER.remove();
    }
}
