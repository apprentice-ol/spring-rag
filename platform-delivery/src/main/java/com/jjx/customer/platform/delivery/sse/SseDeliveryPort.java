package com.jjx.customer.platform.delivery.sse;

import com.jjx.ai.llmobservability.observation.logging.TelemetryLogger;
import com.jjx.customer.platform.business.trace.AgentTraceService;
import com.jjx.customer.platform.business.trace.model.TraceView;
import com.jjx.customer.platform.clarify.ClarifyRequest;
import com.jjx.customer.platform.delivery.DeliveryPort;
import com.jjx.customer.platform.delivery.message.ChatMessageWriter;
import com.jjx.customer.platform.delivery.runtime.ActiveStreamRegistry;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;

/**
 * SSE 交付端口实现：一次请求一个实例（绑定 emitter 与会话/范式上下文）。
 *
 * <p>编排层只调 {@link DeliveryPort} 的方法；本类负责 SSE 事件、消息落库、
 * 引用下发、轨迹事件与收尾的顺序与细节（与既有交付行为一致）。</p>
 */
public class SseDeliveryPort implements DeliveryPort {

    private static final TelemetryLogger log = TelemetryLogger.of(SseDeliveryPort.class);

    private final SseEventSender sseSender;
    private final ChatMessageWriter messageWriter;
    private final AgentTraceService agentTraceService;
    private final ActiveStreamRegistry streamRegistry;
    private final SseEmitter emitter;
    private final String conversationId;
    private final String otelTraceId;
    private final DeliveryContext context;

    public SseDeliveryPort(SseEventSender sseSender, ChatMessageWriter messageWriter,
                           AgentTraceService agentTraceService, SseEmitter emitter,
                           ActiveStreamRegistry streamRegistry,
                           String conversationId, String otelTraceId, DeliveryContext context) {
        this.sseSender = sseSender;
        this.messageWriter = messageWriter;
        this.agentTraceService = agentTraceService;
        this.streamRegistry = streamRegistry;
        this.emitter = emitter;
        this.conversationId = conversationId;
        this.otelTraceId = otelTraceId;
        this.context = context;
    }

    @Override
    public void emitDelta(String conversationId, String text) {
        sseSender.sendEvent(emitter, text);
    }

    @Override
    public void emitDirect(String conversationId, String text, String paradigm) {
        String answer = StringUtils.hasText(text) ? text : "诊断完成（无结论文本）。";
        Long msgId = messageWriter.saveMessage(conversationId, "assistant", answer);
        log.conversationOutput(answer);
        for (String segment : segments(answer)) {
            sseSender.sendEvent(emitter, segment);
        }
        recordTrace(msgId, paradigm);
        sseSender.sendMetaEvent(emitter, msgId, otelTraceId, paradigm);
        sseSender.completeEmitter(emitter);
    }

    @Override
    public void emitClarify(String conversationId, String text, String paradigm, DeliveryContext ctx) {
        ClarifyRequest clarify = new ClarifyRequest(conversationId, text, List.of());
        String askText = buildAskText(clarify);
        sseSender.sendClarifyEvent(emitter, clarify);
        Long msgId = messageWriter.saveMessage(conversationId, "assistant", askText);
        log.conversationOutput(askText);
        sseSender.sendEvent(emitter, askText);
        recordTrace(msgId, paradigm);
        sseSender.sendMetaEvent(emitter, msgId, otelTraceId, paradigm);
        sseSender.completeEmitter(emitter);
    }

    /** 卡片载荷序列化（Jackson 线程安全，静态复用）。 */
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * 人在环中版追问：结构化请求直出（DECIDE 决策移交带证据与点选项）。
     * 落库/正文文案与 SSE 事件同源（buildAskText 渲染），纯文本用户不点按钮也能照常回复。
     */
    @Override
    public void emitClarify(String conversationId, ClarifyRequest clarify, String paradigm, DeliveryContext ctx) {
        String askText = buildAskText(clarify);
        sseSender.sendClarifyEvent(emitter, clarify);
        // 卡片随消息落库：刷新页面后从历史接口读回同款结构，重新渲染成卡片（正文文本还原不出选项/来源）
        Long msgId = messageWriter.saveMessage(conversationId, "assistant", askText, null, toJson(clarify));
        log.conversationOutput(askText);
        sseSender.sendEvent(emitter, askText);
        recordTrace(msgId, paradigm);
        sseSender.sendMetaEvent(emitter, msgId, otelTraceId, paradigm);
        sseSender.completeEmitter(emitter);
    }

    @Override
    public void emitEscalate(String conversationId, String text, String paradigm, DeliveryContext ctx) {
        String reason = StringUtils.hasText(text) ? text : "目前掌握的信息还不足以继续排查。";
        String askText = "排查在这里卡住了：" + reason + "\n\n请补充相关信息（环境/接口/时间/报错/报文），我会继续。";
        Long msgId = messageWriter.saveMessage(conversationId, "assistant", askText);
        log.conversationOutput(askText);
        sseSender.sendEvent(emitter, askText);
        recordTrace(msgId, paradigm);
        sseSender.sendMetaEvent(emitter, msgId, otelTraceId, paradigm);
        sseSender.completeEmitter(emitter);
    }

    @Override
    public void emitCitations(String conversationId, String citationsJson) {
        sseSender.sendCitationsEvent(emitter, citationsJson);
    }

    @Override
    public void emitTrace(String conversationId, Object trace) {
        if (trace instanceof TraceView view) {
            sseSender.sendObsEvent(emitter, view);
        }
    }

    @Override
    public void emitComplete(String conversationId, Long messageId, String otelTraceId, String paradigm) {
        sseSender.sendMetaEvent(emitter, messageId, otelTraceId, paradigm);
        sseSender.completeEmitter(emitter);
    }

    @Override
    public void emitCachedReplay(String conversationId, String answer, String citationsJson, String paradigm) {
        sseSender.sendCitationsEvent(emitter, citationsJson);
        log.conversationOutput(answer);
        for (String segment : segments(answer)) {
            sseSender.sendEvent(emitter, segment);
        }
        Long msgId = messageWriter.saveMessage(conversationId, "assistant", answer, citationsJson);
        sseSender.sendMetaEvent(emitter, msgId, otelTraceId, paradigm);
        sseSender.completeEmitter(emitter);
    }

    @Override
    public Long emitNotice(String conversationId, String text, String otelTraceId) {
        Long msgId = messageWriter.saveMessage(conversationId, "assistant", text);
        log.conversationOutput(text);
        sseSender.sendEvent(emitter, text);
        sseSender.sendMetaEvent(emitter, msgId, otelTraceId, null);
        sseSender.completeEmitter(emitter);
        return msgId;
    }

    @Override
    public void beginRequest(String conversationId) {
        if (streamRegistry != null) {
            streamRegistry.register(conversationId, () -> sseSender.completeEmitter(emitter));
        }
    }

    @Override
    public void endRequest(String conversationId) {
        if (streamRegistry != null) {
            streamRegistry.unregister(conversationId);
        }
    }

    @Override
    public Long persistAnswerOnly(String conversationId, String answer) {
        return messageWriter.saveMessage(conversationId, "assistant", answer);
    }

    @Override
    public Long persistAnswer(String conversationId, String question, String paradigm, String answer,
                              String citationsJson, Object trace, String otelTraceId) {
        Long msgId = messageWriter.saveMessage(conversationId, "assistant", answer, citationsJson);
        if (trace instanceof TraceView view) {
            agentTraceService.record(conversationId, msgId, paradigm, question, view, otelTraceId);
        }
        return msgId;
    }

    @Override
    public void emitDegraded(String conversationId, String otelTraceId) {
        String msg = "系统当前繁忙（缓存服务降级中），请稍后重试。";
        Long msgId = messageWriter.saveMessage(conversationId, "assistant", msg);
        log.conversationOutput(msg);
        sseSender.sendEvent(emitter, msg);
        sseSender.sendMetaEvent(emitter, msgId, otelTraceId, null);
        sseSender.completeEmitter(emitter);
    }

    @Override
    public void emitStream(String conversationId, org.reactivestreams.Publisher<String> tokens, StreamSpec spec) {
        StringBuilder buffer = spec.buffer() != null ? spec.buffer() : new StringBuilder();
        reactor.core.Disposable disposable = reactor.core.publisher.Flux.from(tokens).subscribe(
                content -> {
                    buffer.append(content);
                    sseSender.sendEvent(emitter, content);
                },
                error -> {
                    log.error("[交付] 流式输出失败", error);
                    String partial = buffer.toString();
                    if (spec.outputSink() != null) {
                        spec.outputSink().accept(partial.isEmpty()
                                ? "[stream error] " + error.getMessage() : partial);
                    }
                    if (spec.failureMode() == StreamFailureMode.SIGNAL_ERROR) {
                        try {
                            emitter.completeWithError(error);
                        } catch (Exception e) {
                            // 已完成或已断开，忽略
                        }
                    } else {
                        sseSender.completeEmitter(emitter);
                    }
                    if (spec.onClose() != null) {
                        spec.onClose().run();
                    }
                },
                () -> {
                    String answer = buffer.toString();
                    if (spec.outputSink() != null) {
                        spec.outputSink().accept(answer);
                    }
                    Long msgId = spec.persistFinal() != null ? spec.persistFinal().apply(answer) : null;
                    if (spec.onSuccessExtra() != null) {
                        spec.onSuccessExtra().accept(answer);
                    }
                    sseSender.sendMetaEvent(emitter, msgId, spec.otelTraceId(), spec.paradigm());
                    sseSender.completeEmitter(emitter);
                    if (spec.onClose() != null) {
                        spec.onClose().run();
                    }
                });
        Runnable cancel = () -> {
            if (spec.onClose() != null) {
                spec.onClose().run();
            }
            if (disposable.isDisposed()) {
                return;
            }
            disposable.dispose();
            String partial = buffer.toString();
            if (!partial.isEmpty() && spec.outputSink() != null) {
                try {
                    spec.outputSink().accept(partial);
                } catch (Throwable t) {
                    log.warn("[交付] 取消时写 trace output 失败", t);
                }
            }
            if (spec.persistPartial() != null) {
                spec.persistPartial().run();
            }
            log.warn("[交付] 流已取消（停止生成/断连/超时），会话ID={}, 已生成 {} 字符",
                    spec.conversationId(), partial.length());
            sseSender.completeEmitter(emitter);
        };
        if (streamRegistry != null) {
            streamRegistry.register(spec.conversationId(), cancel);
        }
        if (spec.onSubscribed() != null) {
            spec.onSubscribed().accept(disposable);
        }
    }

    /** 结构化载荷序列化（失败按 null 落库——卡片刷新后不渲染，但绝不阻断对话）。 */
    private String toJson(Object payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("[[SSE]] 卡片载荷序列化失败（刷新后不重渲染）: {}", e.getMessage());
            return null;
        }
    }

    /** 追问文案：问齐 = summary + 缺失项清单；决策移交 = 问句 + 证据要点 + 选项（与 clarify 事件卡片一致）。 */
    static String buildAskText(ClarifyRequest clarify) {
        StringBuilder sb = new StringBuilder();
        if (clarify.isDecision()) {
            sb.append(clarify.summary() == null || clarify.summary().isBlank()
                    ? "排查需要你的判断" : clarify.summary()).append('\n');
            if (!clarify.evidence().isEmpty()) {
                sb.append("\n已查明：\n");
                for (String point : clarify.evidence()) {
                    sb.append("- ").append(point).append('\n');
                }
            }
            if (!clarify.options().isEmpty()) {
                sb.append("\n你可以：\n");
                for (ClarifyRequest.ClarifyChoice choice : clarify.options()) {
                    // 点选按钮走前端 #decision:value；纯文本用户直接回复说明同样生效
                    sb.append("- ").append(choice.label()).append('\n');
                }
                sb.append("\n（直接回复补充信息也可以，我将按新线索继续排查）");
            }
            return sb.toString().trim();
        }
        sb.append(clarify.summary() == null || clarify.summary().isBlank()
                ? "需要补充以下信息" : clarify.summary()).append("：\n");
        int n = 0;
        for (var q : clarify.questions()) {
            sb.append(++n).append(". **").append(q.question()).append("**");
            if (q.hint() != null && !q.hint().isBlank()) {
                sb.append("（").append(q.hint()).append("）");
            }
            sb.append('\n');
        }
        if (!clarify.evidence().isEmpty()) {
            sb.append("\n已自动补全（如有误请直接指出）：\n");
            for (String point : clarify.evidence()) {
                sb.append("- ").append(point).append('\n');
            }
        }
        return sb.toString().trim();
    }

    /** 分段：按二级标题切段。 */
    static List<String> segments(String answer) {
        List<String> segs = new ArrayList<>();
        for (String p : answer.split("(?=^## )", -1)) {
            if (!p.isBlank()) {
                segs.add(p.strip() + "\n\n");
            }
        }
        return segs;
    }

    private void recordTrace(Long msgId, String paradigm) {
        TraceView trace = context != null && context.trace() instanceof TraceView view ? view : null;
        String question = context != null ? context.question() : null;
        if (trace != null) {
            agentTraceService.record(conversationId, msgId, paradigm, question, trace, otelTraceId);
        }
    }
}
