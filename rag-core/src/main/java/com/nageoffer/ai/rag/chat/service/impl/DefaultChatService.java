package com.nageoffer.ai.rag.chat.service.impl;

import com.nageoffer.ai.rag.chat.service.ChatService;
import com.nageoffer.ai.rag.chat.service.pipeline.StreamChatPipeline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 流式问答实现（适配新版管道）。
 * <p>
 * 委托 {@link StreamChatPipeline} 执行完整的意图识别 → 多通道检索 →
 * 后处理 → 流式回答编排。替代原来的单路 ragChatClient（QuestionAnswerAdvisor）方案。
 * </p>
 */
@Slf4j
@Service
public class DefaultChatService implements ChatService {

    private final StreamChatPipeline pipeline;

    public DefaultChatService(StreamChatPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Override
    public void streamChat(String question, String conversationId, String agent, SseEmitter emitter) {
        pipeline.execute(question, conversationId, agent, emitter);
    }
}
