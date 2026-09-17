package com.jjx.customer.platform.delivery.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.ai.llmobservability.observation.logging.TelemetryLogger;
import com.jjx.customer.platform.business.trace.model.TraceView;
import com.jjx.customer.platform.clarify.ClarifyRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * SSE 事件发送器：{@code /chat/stream} 的全部事件副作用收口（由交付端口调用，编排层不直接接触）。
 * <p>事件名（message/trace/citations/clarify/meta）与发送顺序是前端 {@code chat.ts} 的消费契约
 * （flush 分支 trace→citations→meta→clarify→error→message），不可变更。
 * <p>所有 send 均吞异常（断连/序列化失败绝不阻断回答主链路），失败只记日志；
 * 增强类事件（trace/citations/meta）失败降级 debug，追问/正文失败 warn。
 */
@Component
@RequiredArgsConstructor
public class SseEventSender {

    private static final TelemetryLogger log = TelemetryLogger.of(SseEventSender.class);

    private final ObjectMapper objectMapper;

    /** 发送回答增量块（message 事件，默认事件）。 */
    public void sendEvent(SseEmitter emitter, String content) {
        try {
            emitter.send(SseEmitter.event().data(content).name("message"));
        } catch (IOException e) {
            // 客户端断开，忽略
            log.debug("[SSE] message 写入失败（客户端可能已断开）: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("[SSE] message 发送异常", e);
        }
    }

    /** 发送 agent 执行轨迹（trace 事件，前端对照面板渲染用；一次性，在流式回答前发出）。 */
    public void sendObsEvent(SseEmitter emitter, TraceView trace) {
        if (trace == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(trace);
            emitter.send(SseEmitter.event().name("trace").data(json));
        } catch (Exception e) {
            log.debug("[SSE] trace 事件发送失败（忽略）: {}", e.getMessage());
        }
    }

    /** 发送引用溯源映射（citations 事件，流式开始前一次性）：ref → 文档信息，前端渲染 [N] 角标与来源面板。 */
    public void sendCitationsEvent(SseEmitter emitter, String citationsJson) {
        if (citationsJson == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name("citations").data(citationsJson));
        } catch (Exception e) {
            log.debug("[SSE] citations 事件发送失败（忽略）: {}", e.getMessage());
        }
    }

    /** clarify SSE 事件（结构化缺失槽位，前端渲染追问卡片）。 */
    public void sendClarifyEvent(SseEmitter emitter, ClarifyRequest clarify) {
        try {
            emitter.send(SseEmitter.event().name("clarify")
                    .data(objectMapper.writeValueAsString(clarify)));
        } catch (Exception e) {
            log.warn("[SSE] clarify 事件发送失败: {}", e.getMessage());
        }
    }

    /**
     * 发送消息元信息（meta 事件，流末尾）：messageId（关联 sa_message）/ traceId（跳 OpenObserve 全链路）/ paradigm。
     * 前端把它绑定到 assistant 气泡，实现"消息 ↔ 轨迹 ↔ OO 链路"三方关联。
     */
    public void sendMetaEvent(SseEmitter emitter, Long messageId, String traceId, String paradigm) {
        try {
            Map<String, Object> meta = new HashMap<>();
            meta.put("messageId", messageId);
            meta.put("traceId", traceId);
            meta.put("paradigm", paradigm);
            emitter.send(SseEmitter.event().name("meta").data(objectMapper.writeValueAsString(meta)));
        } catch (Exception e) {
            log.debug("[SSE] meta 事件发送失败（忽略）: {}", e.getMessage());
        }
    }

    public void completeEmitter(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception e) {
            // 已完成或已断开，忽略
        }
    }

    /** 序列化失败返回 null（引用溯源是增强功能，绝不能阻断回答主链路）。 */
    public String toJsonOrNull(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("[SSE] 序列化失败（忽略）: {}", e.getMessage());
            return null;
        }
    }
}
