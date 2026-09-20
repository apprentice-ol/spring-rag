package com.jjx.customer.platform.business.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.ai.llmobservability.observation.TelemetryTemplate;
import com.jjx.ai.llmobservability.observation.logging.TelemetryLogger;
import com.jjx.customer.platform.business.knowledge.rag.RagContextAssembler;
import com.jjx.customer.platform.business.runtime.DegradeGuard;
import com.jjx.customer.platform.business.trace.model.TraceView;
import com.jjx.customer.platform.delivery.DeliveryPort;
import com.jjx.customer.platform.delivery.DeliveryPortFactory;
import com.jjx.customer.platform.knowledge.answer.KnowledgeAnswerService;
import com.jjx.customer.platform.knowledge.retrieval.RetrievedChunk;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * RAG 流式回答器（自 {@link ChatOrchestrator} 决策链第 10 步拆出）：
 * 装配 RAG 上下文并流式回答；落库、轨迹补步、缓存写回经完成回调收口。
 *
 * <p>系统提示词（回答规则）由 ragChatClient 的 defaultSystem 承载
 * （prompts/chat/pipeline/rag-answer-system.md），此处只把检索资料与用户问题组装进
 * user message——避免把每次都变的资料拼进 system 而破坏 prompt cache，也让规则稳定可缓存。</p>
 *
 * <p>生成能力在 {@link KnowledgeAnswerService#answer}（{@code @TelemetryStep} 自动埋点），
 * 上下文组装在 {@link RagContextAssembler}；本类只做装配与端口调用。</p>
 */
@Component
@RequiredArgsConstructor
public class RagAnswerStreamer<S> {

    private static final TelemetryLogger log = TelemetryLogger.of(RagAnswerStreamer.class);

    private final RagContextAssembler ragContextAssembler;
    private final KnowledgeAnswerService knowledgeAnswerService;
    private final DeliveryPortFactory<S> deliveryPortFactory;
    private final AnswerCacheCoordinator<S> answerCacheCoordinator;
    private final DegradeGate<S> degradeGate;
    private final ObjectMapper objectMapper;

    /** 流式回答落库/收尾专用（虚拟线程）：complete 回调跑在 reactor 事件循环上，阻塞 JDBC 必须移出 */
    @Qualifier("chatPersistExecutor")
    private final ExecutorService chatPersistExecutor;

    /**
     * 装配上下文并流式回答（只列本文档新增的参数，其余见调用方 {@code ChatOrchestrator} 决策链）。
     *
     * @param history    最近若干轮对话历史（空串 = 首轮）。只用于让模型认出"它/这个"指什么，
     *                   不构成事实来源——事实仍只来自本次检索资料（见 rag-answer-kb 的「对话历史的使用边界」）
     * @param cacheable  本轮答案是否可跨会话复用（带历史时 false，见
     *                   {@link AnswerCacheCoordinator#storeAnswer}）
     */
    public void streamRagResponse(String question, String conversationId,
                                  List<RetrievedChunk> chunks, S sink, long t0,
                                  String paradigm, TraceView trace, String otelTraceId,
                                  String answerCacheKey, String normalizedQuestion,
                                  String history, boolean cacheable) {
        // 降级闸：Redis 断路器 OPEN 期间收紧 LLM 流式并发（缓存命中/重放路径不经过此处，天然不受限）
        DegradeGuard.Lease lease = degradeGate.tryAcquire(conversationId, sink, otelTraceId);
        if (lease == null) {
            return;
        }

        RagContextAssembler.RagContext ragContext = ragContextAssembler.buildContextText(chunks);
        log.info("[对话编排] 给 LLM 的上下文: {}块{}文档 {}字符",
                chunks.size(), ragContext.docCount(), ragContext.text().length());

        // 引用映射先行下发（流式开始前，与 trace 事件同模式）：正文里的 [N] 角标靠它渲染
        String citationsJson = toJsonOrNull(ragContext.citations());

        DeliveryPort port = deliveryPortFactory.beginStream(sink, conversationId, question, otelTraceId, paradigm, trace);
        port.emitCitations(conversationId, citationsJson);

        StringBuilder fullAnswer = new StringBuilder();
        // 流式回调线程（reactor-netty）的 ambient HOLDER 常未恢复，subscribe 前于业务线程捕获 output sink
        Consumer<Object> outputSink = log.conversationSink();
        // 停止/断连时部分回答落库（带引用 + 轨迹；与完成回调共享防双写标志）
        AtomicBoolean persisted = new AtomicBoolean();
        Runnable persistPartial = () -> {
            if (persisted.compareAndSet(false, true)) {
                String partial = fullAnswer.toString();
                if (partial.isBlank()) {
                    return;
                }
                chatPersistExecutor.execute(() -> {
                    deliveryPortFactory.begin(sink, conversationId, question, otelTraceId, paradigm, trace)
                            .persistAnswer(conversationId, question, paradigm, partial, citationsJson,
                                    trace, otelTraceId);
                });
            }
        };
        // 生成段的起始时刻：收尾时据此算出「生成答案」那一步的耗时（见 appendAnswerStep）
        long tAnswer = System.currentTimeMillis();
        // 流式交付交给交付层：逐 token 送达 + 完成/失败收口；落库与缓存写回仍由编排层提供回调（行为不变）
        DeliveryPort.StreamSpec spec = new DeliveryPort.StreamSpec(conversationId, question, paradigm, otelTraceId,
                trace, fullAnswer, outputSink,
                answer -> {
                    if (!persisted.compareAndSet(false, true)) {
                        return null;
                    }
                    try {
                        return CompletableFuture.supplyAsync(
                                        () -> {
                                            // 生成段的收尾步：答案正文就是气泡本身，轨迹里只留一行读数
                                            // （与 agent-framework 的 answer 节点同口径）。它只能在这里补——
                                            // 「生成」要等流结束才成立，而轨迹事件在流开始前就发过一版了，
                                            // 所以补完要连完整轨迹再发一次（前端覆盖式接收）。
                                            appendAnswerStep(trace, chunks, question, answer, tAnswer);
                                            Long msgId = deliveryPortFactory
                                                    .begin(sink, conversationId, question, otelTraceId,
                                                            paradigm, trace)
                                                    .persistAnswer(conversationId, question, paradigm, answer,
                                                            citationsJson, trace, otelTraceId);
                                            deliveryPortFactory
                                                    .begin(sink, conversationId, question, otelTraceId,
                                                            paradigm, trace)
                                                    .emitTrace(conversationId, trace);
                                            log.info("========== [对话编排] 完成 ========== 会话ID={}, 耗时={}ms",
                                                    conversationId, System.currentTimeMillis() - t0);
                                            answerCacheCoordinator.storeAnswer(answerCacheKey, answer,
                                                    citationsJson, paradigm, normalizedQuestion, msgId, cacheable);
                                            return msgId;
                                        },
                                        chatPersistExecutor)
                                .get(5, TimeUnit.SECONDS);
                    } catch (Exception persistEx) {
                        log.warn("[对话编排] 保存助手消息/轨迹/缓存失败", persistEx);
                        return null;
                    }
                },
                persistPartial, null,
                DeliveryPort.StreamFailureMode.SIGNAL_ERROR,
                null,
                lease::close);
        // systemPrompt=null：P2 快照装配未接线，服务侧回退 classpath 基线
        port.emitStream(conversationId,
                knowledgeAnswerService.answer(question, ragContext.text(), history, null), spec);
    }

    /**
     * 把「生成答案」补成轨迹的最后一步。
     *
     * <p>它和前面那些步不是同一种东西：检索链的每一步都在流开始前就跑完了，而「生成」要等
     * 流结束才成立。所以这一步只能在落库前追加，并随完整轨迹重发一次——早发的那一版
     * 到检索为止，用户中途点开也有得看，收尾这一版才是完整的一轮。</p>
     *
     * <p>产物只写一行读数：答案全文就是气泡正文，在轨迹里再铺一遍是重复。</p>
     */
    private static void appendAnswerStep(TraceView trace, List<RetrievedChunk> chunks, String question,
                                         String answer, long startedAt) {
        if (trace == null) {
            return;
        }
        String asked = question == null ? "" : question;
        if (asked.length() > 40) {
            asked = asked.substring(0, 40) + "…";
        }
        String input = String.format("提问「%s」 + 资料 %d 条", asked, chunks == null ? 0 : chunks.size());
        String output = String.format("答案 %d 字", answer == null ? 0 : answer.length());
        trace.step("answer", null, input, output, startedAt);
    }

    /** 引用映射序列化（失败返回 null：引用溯源是增强功能，绝不能阻断回答主链路）。 */
    private String toJsonOrNull(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("[对话编排] 序列化失败（忽略）: {}", e.getMessage());
            return null;
        }
    }
}
