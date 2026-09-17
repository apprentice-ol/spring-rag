package com.jjx.customer.platform.delivery;

/**
 * 交付端口（SPI）：编排层只负责"产出什么"，交付层决定"怎么送到用户"。
 *
 * <p>方向：business（编排/调度）依赖本接口；delivery 模块提供实现（SSE 事件 / 消息落库 / 引用下发 /
 * 流生命周期）。因此编排层不出现任何传输协议、持久化与流生命周期细节。</p>
 *
 * <p>会话上下文的读取/写入见 {@link com.jjx.customer.platform.conversation.ConversationStore}。</p>
 */
public interface DeliveryPort {

    /** 流式增量（打字机）。 */
    void emitDelta(String conversationId, String text);

    /** 直答/结论（一次性或分段交付）。 */
    void emitDirect(String conversationId, String text, String paradigm);

    /** 追问（结构化澄清）。 */
    void emitClarify(String conversationId, String text, String paradigm, DeliveryContext context);

    /** 升级/卡住说明（转追问文案）。 */
    void emitEscalate(String conversationId, String text, String paradigm, DeliveryContext context);

    /** 引用溯源（流式开始前下发）。 */
    void emitCitations(String conversationId, String citationsJson);

    /** 执行轨迹（对照面板）。 */
    void emitTrace(String conversationId, Object trace);

    /** 收尾（meta + complete）。 */
    void emitComplete(String conversationId, Long messageId, String otelTraceId, String paradigm);

    /** 缓存重放交付：引用事件 + 答案分段 + 带引用的消息落库 + 收尾（内容来自缓存，不经生成）。 */
    void emitCachedReplay(String conversationId, String answer, String citationsJson, String paradigm);

    /**
     * 一次性通知交付（不生成、不分段）：落库 → 单条文本事件 → meta → 收尾。
     *
     * @return 落库后的消息 id（供调用方关联轨迹）
     */
    Long emitNotice(String conversationId, String text, String otelTraceId);

    /** 请求入口：注册初始取消句柄（流式阶段会被"完全体取消句柄"覆盖）。 */
    void beginRequest(String conversationId);

    /** 请求收尾：注销取消句柄。 */
    void endRequest(String conversationId);

    /** 助手消息落库（无引用、不记轨迹）：闲聊等纯文本回答。 */
    Long persistAnswerOnly(String conversationId, String answer);

    /** 助手消息落库（带引用）+ 轨迹记录：RAG 回答。 */
    Long persistAnswer(String conversationId, String question, String paradigm, String answer,
                       String citationsJson, Object trace, String otelTraceId);

    /** 降级拒绝（并发闸/断路器）。 */
    void emitDegraded(String conversationId, String otelTraceId);

    /**
     * 流式交付：订阅 token 流，逐段送达；异常/完成/取消三条路径由本实现统一收口。
     *
     * @param tokens 上游流（LLM 生成）
     * @param spec   交付规格（上下文、回调与收口动作，均由编排层装配）
     */
    void emitStream(String conversationId, org.reactivestreams.Publisher<String> tokens, StreamSpec spec);

    /**
     * 流式交付规格：编排层装配，交付层执行。
     *
     * @param conversationId  会话
     * @param question        原始问题（轨迹落库用）
     * @param paradigm        范式（meta 用，可空）
     * @param otelTraceId     OTel traceId（meta 用）
     * @param trace           轨迹视图（可空）
     * @param buffer          累积缓冲（编排层持有，用于取消/异常时部分落库与 trace output）
     * @param outputSink      trace output 覆盖写（reactor 线程不可用 HOLDER，须业务线程捕获后传入）
     * @param persistFinal    完成后落库（入参=终稿，返回 msgId）
     * @param persistPartial  取消/异常时部分落库（内部自带防双写）
     * @param onSuccessExtra  成功后的附加动作（如答案缓存写回），可空
     * @param failureMode     失败时的收尾方式（普通 complete / 以错误收尾），可选
     * @param onSubscribed    订阅后回调（交付侧据此注册取消句柄）
     * @param onClose         收口动作（如关闭并发闸 lease），幂等
     */
    record StreamSpec(String conversationId, String question, String paradigm, String otelTraceId,
                      Object trace,
                      StringBuilder buffer,
                      java.util.function.Consumer<Object> outputSink,
                      java.util.function.Function<String, Long> persistFinal,
                      Runnable persistPartial,
                      java.util.function.Consumer<String> onSuccessExtra,
                      StreamFailureMode failureMode,
                      java.util.function.Consumer<reactor.core.Disposable> onSubscribed,
                      Runnable onClose) {
    }

    /** 流式失败时的收尾方式（由编排层声明意图，交付层执行）。 */
    enum StreamFailureMode {
        /** 正常收尾（complete）：与既有闲聊流一致。 */
        COMPLETE,
        /** 以错误收尾（completeWithError）：前端按错误处理，与既有 RAG 流一致。 */
        SIGNAL_ERROR
    }

    /** 一次交付的上下文（问题、范式、轨迹引用、缓存 key 等编排产物，交付侧只读）。 */
    record DeliveryContext(String question, String paradigm, Object trace, String otelTraceId,
                           String answerCacheKey, String normalizedQuestion) {
    }
}
