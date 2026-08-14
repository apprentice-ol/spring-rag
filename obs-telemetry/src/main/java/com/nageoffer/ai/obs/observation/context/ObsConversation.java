package com.nageoffer.ai.obs.observation.context;

import com.nageoffer.ai.obs.observation.span.CapturedSpan;
import com.nageoffer.ai.obs.observation.span.SpanWriter;
import com.nageoffer.ai.obs.observation.support.SpanIoLimits;
import com.nageoffer.ai.obs.observation.ObservationPipeline;
import com.nageoffer.ai.obs.observation.event.ObsEvent;
import io.opentelemetry.api.trace.Span;

/**
 * 一次请求/对话的环境级 ambient 上下文：承载 trace 级 input/output（用户问题 / 最终回答）。
 *
 * <p><b>所属维度</b>：②event（@internal，业务经门面 {@code conversationOutput} 使用，不直接依赖）。</p>
 *
 * <p><b>职责</b>：把对话级 IO 写到 HTTP 根 span——产 TRACE_IO 事件走 {@link ObservationPipeline}（原文不摘要，
 * 仅截断）。input 只设一次，output 可多次（以最后一次为准）。</p>
 *
 * <p><b>协作</b>：由 {@code ObsTemplate.beginConversation} 构造（捕获当前 server span）；经
 * {@code ObsConversationAccessor} 跨线程透传；output 由业务经门面写。</p>
 *
 * <p><b>为何持 {@link CapturedSpan}</b>：回答在 Reactor 流式回调线程产生，{@code Span.current()}
 * 不保证恢复为根 span，持构造期捕获引用才稳。</p>
 */
public final class ObsConversation {

    private final SpanWriter rootWriter;
    private final ObservationPipeline pipeline;
    private volatile boolean inputSet;

    public ObsConversation(Span rootSpan, ObservationPipeline pipeline) {
        this.rootWriter = new CapturedSpan(rootSpan);
        this.pipeline = pipeline;
    }

    /** 记 trace 级 input（首个 user 消息 = 用户问题）。重复调用忽略。 */
    public void input(Object value) {
        if (inputSet || value == null) {
            return;
        }
        inputSet = true;
        emitIo(SpanIoLimits.KEY_TRACE_INPUT, value);
    }

    /** 记 trace 级 output（assistant 回答）。同一请求内多次以最后一次为准。 */
    public void output(Object value) {
        emitIo(SpanIoLimits.KEY_TRACE_OUTPUT, value);
    }

    private void emitIo(String key, Object value) {
        ObsEvent event = new ObsEvent(ObsEvent.EventType.TRACE_IO, "conversation", null, value);
        event.setIoKey(key);
        pipeline.emit(event, rootWriter);
    }
}
