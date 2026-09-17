package com.jjx.customer.platform.delivery;

/**
 * 交付端口工厂（SPI）：编排层只依赖本接口与 {@link DeliveryPort} 契约。
 *
 * <p>方向：business（编排/决策）依赖本接口；delivery 模块提供实现，负责把端口绑定到具体传输载体
 * （SSE emitter / WebSocket 会话 / 批处理收集器等）。因此编排层代码里不会出现任何传输类型。</p>
 *
 * @param <S> 传输载体类型（如 {@code SseEmitter}）：对编排层不透明，只负责透传回实现方
 */
public interface DeliveryPortFactory<S> {

    /**
     * 取一次请求的交付端口（一次性事件 + 落库）。
     *
     * @param sink           传输载体（不透明句柄）
     * @param conversationId 会话 ID
     * @param question       本轮问题（轨迹落库用，可空）
     * @param otelTraceId    OTel traceId（meta 事件用，可空）
     * @param paradigm       范式（meta 事件用，可空）
     * @param trace          执行轨迹（trace 事件用，可空）
     */
    DeliveryPort begin(S sink, String conversationId, String question,
                       String otelTraceId, String paradigm, Object trace);

    /**
     * 取一次请求的流式交付端口（逐 token 送达 + 收口）。
     * 语义与 {@link #begin} 相同：同一请求可多次调用，每次由实现方绑定同一载体。
     */
    DeliveryPort beginStream(S sink, String conversationId, String question,
                             String otelTraceId, String paradigm, Object trace);
}
