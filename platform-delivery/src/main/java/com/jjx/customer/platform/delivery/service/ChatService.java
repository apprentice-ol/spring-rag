package com.jjx.customer.platform.delivery.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 流式问答服务。 */
public interface ChatService {

    /**
     * 流式回答：把模型输出逐块推送到 emitter。
     *
     * @param question       用户问题
     * @param conversationId 会话 ID（同一 ID 自动带上历史记忆）
     * @param agent          agent 范式（RAG 链内检索编排用，兼容旧客户端/评测），null/空 用配置默认
     * @param agentChoice    用户显式选择的范式（区别于 agent：只在用户主动选择时由新前端发送，
     *                       参与意图路由决策——ops_diagnose 直接进诊断 / knowledge 压制诊断劫持），
     *                       null/空 = 自动档（意图识别路由）
     * @param emitter        SSE 输出端
     */
    void streamChat(String question, String conversationId, String agent, String agentChoice, SseEmitter emitter);
}
