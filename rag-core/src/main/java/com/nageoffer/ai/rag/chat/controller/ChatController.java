package com.nageoffer.ai.rag.chat.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nageoffer.ai.rag.chat.dao.entity.ConversationEntity;
import com.nageoffer.ai.rag.chat.dao.entity.MessageEntity;
import com.nageoffer.ai.rag.chat.dao.mapper.ConversationMapper;
import com.nageoffer.ai.rag.chat.dao.mapper.MessageMapper;
import com.nageoffer.ai.rag.chat.service.ChatService;
import com.nageoffer.ai.llmobservability.observation.annotation.TelemetryStep;
import com.nageoffer.ai.llmobservability.observation.annotation.TelemetryConversation;
import com.nageoffer.ai.llmobservability.observation.propagation.ContextPropagator;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 流式问答入口（SSE）+ 会话管理。 */
@Slf4j
@RestController
@RequestMapping("/chat")
public class ChatController {

    private final ChatService chatService;
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;

    public ChatController(ChatService chatService,
                          ConversationMapper conversationMapper,
                          MessageMapper messageMapper) {
        this.chatService = chatService;
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
    }



    @TelemetryStep("rag.chat")
    @TelemetryConversation
    @RequestMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE,
                    method = {RequestMethod.GET, RequestMethod.POST})
    public SseEmitter stream(@RequestParam String question,
                             @RequestParam(required = false) String conversationId,
                             @RequestParam(required = false) String agent) {
        // 5 分钟超时
        SseEmitter emitter = new SseEmitter(300_000L);
        Thread.ofVirtual().start(ContextPropagator.wrap(() -> {
            try {
                chatService.streamChat(question, conversationId, agent, emitter);
            } catch (Exception e) {
                log.error("[ChatController] 流式处理异常", e);
                try { emitter.completeWithError(e); } catch (Exception ignored) {}
            }
        }));
        return emitter;
    }




    @GetMapping("/conversations")
    public List<ConversationEntity> listConversations() {
        return conversationMapper.selectList(
                new LambdaQueryWrapper<ConversationEntity>()
                        .orderByDesc(ConversationEntity::getUpdatedAt));
    }

    @PostMapping("/conversations")
    public ConversationEntity createConversation(@RequestBody Map<String, String> body) {
        ConversationEntity c = new ConversationEntity();
        c.setConversationId(UUID.randomUUID().toString());
        c.setTitle(body.getOrDefault("title", "新对话"));
        c.setCreatedAt(LocalDateTime.now());
        c.setUpdatedAt(LocalDateTime.now());
        conversationMapper.insert(c);
        return c;
    }



    @GetMapping("/conversations/{conversationId}/messages")
    public List<MessageEntity> getMessages(@PathVariable String conversationId) {
        return messageMapper.selectList(
                new LambdaQueryWrapper<MessageEntity>()
                        .eq(MessageEntity::getConversationId, conversationId)
                        .orderByAsc(MessageEntity::getCreatedAt));
    }

    @Transactional
    @DeleteMapping("/conversations/{conversationId}")
    public Map<String, String> deleteConversation(@PathVariable String conversationId) {


        messageMapper.delete(new LambdaQueryWrapper<MessageEntity>()
                .eq(MessageEntity::getConversationId, conversationId));
        conversationMapper.delete(new LambdaQueryWrapper<ConversationEntity>()
                .eq(ConversationEntity::getConversationId, conversationId));
        return Map.of("status", "deleted");
    }
}
