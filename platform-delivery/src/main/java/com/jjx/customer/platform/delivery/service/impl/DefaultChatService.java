package com.jjx.customer.platform.delivery.service.impl;

import com.jjx.customer.platform.business.orchestration.ChatOrchestrator;
import com.jjx.customer.platform.delivery.service.ChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 流式问答实现：delivery 侧入口薄壳，决策与编排全部委托给 business 的 {@link ChatOrchestrator}。
 * <p>
 * 本类只负责把 SSE 载体交给编排器并绑定交付端口（{@code SseDeliveryPortFactory} 由 Spring 注入到编排器）。
 * </p>
 */
@Slf4j
@Service
public class DefaultChatService implements ChatService {

    private final ChatOrchestrator<SseEmitter> orchestrator;

    public DefaultChatService(ChatOrchestrator<SseEmitter> orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Override
    public void streamChat(String question, String conversationId, String agent, String agentChoice,
                           String autonomy, SseEmitter emitter) {
        orchestrator.execute(question, conversationId, agent, agentChoice, autonomy, emitter);
    }
}
