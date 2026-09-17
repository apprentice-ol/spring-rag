package com.jjx.customer.platform.delivery.sse;

import com.jjx.customer.platform.business.trace.AgentTraceService;
import com.jjx.customer.platform.delivery.DeliveryPort;
import com.jjx.customer.platform.delivery.DeliveryPortFactory;
import com.jjx.customer.platform.delivery.message.ChatMessageWriter;
import com.jjx.customer.platform.delivery.runtime.ActiveStreamRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 交付端口工厂（SSE 实现）：按请求创建 {@link SseDeliveryPort}（绑定 emitter 与上下文）。
 * 编排层只依赖 {@link DeliveryPortFactory} 与 {@link DeliveryPort} 契约，本类是 delivery 模块的实现侧装配。
 */
@Component
@RequiredArgsConstructor
public class SseDeliveryPortFactory implements DeliveryPortFactory<SseEmitter> {

    private final SseEventSender sseSender;
    private final ChatMessageWriter messageWriter;
    private final AgentTraceService agentTraceService;
    private final ActiveStreamRegistry streamRegistry;

    @Override
    public DeliveryPort begin(SseEmitter emitter, String conversationId, String question,
                              String otelTraceId, String paradigm, Object trace) {
        DeliveryPort.DeliveryContext context = new DeliveryPort.DeliveryContext(
                question, paradigm, trace, otelTraceId, null, null);
        return new SseDeliveryPort(sseSender, messageWriter, agentTraceService,
                emitter, streamRegistry, conversationId, otelTraceId, context);
    }

    /** 流式交付：同一请求上下文的端口实例。 */
    @Override
    public DeliveryPort beginStream(SseEmitter emitter, String conversationId, String question,
                                    String otelTraceId, String paradigm, Object trace) {
        return begin(emitter, conversationId, question, otelTraceId, paradigm, trace);
    }
}
