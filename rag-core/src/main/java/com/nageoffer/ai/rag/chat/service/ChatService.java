package com.nageoffer.ai.rag.chat.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 流式问答服务。 */
public interface ChatService {

    /**
     * 流式回答：把模型输出逐块推送到 emitter。
     *
     * @param question       用户问题
     * @param conversationId 会话 ID（同一 ID 自动带上历史记忆）
     * @param agent          agent 范式（naive/react），null/空 用配置默认
     * @param emitter        SSE 输出端
     */
    void streamChat(String question, String conversationId, String agent, SseEmitter emitter);
}
