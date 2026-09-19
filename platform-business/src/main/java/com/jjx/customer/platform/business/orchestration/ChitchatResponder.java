package com.jjx.customer.platform.business.orchestration;

import com.jjx.ai.llmobservability.observation.logging.TelemetryLogger;
import com.jjx.customer.platform.business.runtime.DegradeGuard;
import com.jjx.customer.platform.delivery.DeliveryPort;
import com.jjx.customer.platform.delivery.DeliveryPortFactory;
import com.jjx.customer.platform.knowledge.answer.KnowledgeAnswerService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 闲聊/非检索意图应答器（自 {@link ChatOrchestrator} 决策链 8.3 步拆出）：
 * 问候/闲聊不检索直接回答（流式 + 落库 + meta）。
 */
@Component
@RequiredArgsConstructor
public class ChitchatResponder<S> {

    private static final TelemetryLogger log = TelemetryLogger.of(ChitchatResponder.class);

    private final KnowledgeAnswerService knowledgeAnswerService;
    private final DeliveryPortFactory<S> deliveryPortFactory;
    private final DegradeGate<S> degradeGate;

    /** 流式回答落库/收尾专用（虚拟线程）：complete 回调跑在 reactor 事件循环上，阻塞 JDBC 必须移出 */
    @Qualifier("chatPersistExecutor")
    private final ExecutorService chatPersistExecutor;

    /**
     * 处理问候/闲聊（不检索，直接回答）。
     */
    public void handleNonQuery(String question, String conversationId, S sink, String otelTraceId) {
        log.info("[对话编排] 走闲聊回复: question=\"{}\"", question);

        DegradeGuard.Lease lease = degradeGate.tryAcquire(conversationId, sink, otelTraceId);
        if (lease == null) {
            return;
        }

        StringBuilder fullAnswer = new StringBuilder();
        // 流式回调线程（reactor-netty）的 ambient HOLDER 常未恢复，subscribe 前于业务线程捕获 output sink
        Consumer<Object> outputSink = log.conversationSink();
        // 停止/断连时部分回答落库（与完成回调共享防双写标志）
        AtomicBoolean persisted = new AtomicBoolean();
        Runnable persistPartial = () -> {
            if (persisted.compareAndSet(false, true)) {
                String partial = fullAnswer.toString();
                if (!partial.isBlank()) {
                    chatPersistExecutor.execute(() ->
                            deliveryPortFactory.begin(sink, conversationId, question, otelTraceId, null, null)
                                    .persistAnswerOnly(conversationId, partial));
                }
            }
        };
        // 流式交付交给交付层：逐 token 送达 + 异常/完成收口
        DeliveryPort.StreamSpec spec = new DeliveryPort.StreamSpec(conversationId, question, null, otelTraceId,
                null, fullAnswer, outputSink,
                answer -> {
                    if (!persisted.compareAndSet(false, true)) {
                        return null;
                    }
                    try {
                        return CompletableFuture.supplyAsync(
                                        () -> deliveryPortFactory
                                                .begin(sink, conversationId, question, otelTraceId, null, null)
                                                .persistAnswerOnly(conversationId, answer),
                                        chatPersistExecutor)
                                .get(5, TimeUnit.SECONDS);
                    } catch (Exception persistEx) {
                        log.warn("[对话编排] 闲聊落库失败", persistEx);
                        return null;
                    }
                },
                persistPartial, null,
                DeliveryPort.StreamFailureMode.COMPLETE,
                null,
                lease::close);
        deliveryPortFactory.beginStream(sink, conversationId, question, otelTraceId, null, null)
                .emitStream(conversationId, knowledgeAnswerService.chitchat(question, conversationId), spec);
    }
}
