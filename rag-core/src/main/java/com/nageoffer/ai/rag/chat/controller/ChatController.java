package com.nageoffer.ai.rag.chat.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nageoffer.ai.rag.chat.agent.AgentTraceService;
import com.nageoffer.ai.rag.chat.dao.entity.ConversationEntity;
import com.nageoffer.ai.rag.chat.dao.entity.MessageEntity;
import com.nageoffer.ai.rag.chat.dao.mapper.ConversationMapper;
import com.nageoffer.ai.rag.chat.dao.mapper.MessageMapper;
import com.nageoffer.ai.rag.chat.service.ChatService;
import com.nageoffer.ai.rag.ingestion.domain.dto.PageResult;
import com.jjx.ai.llmobservability.observation.annotation.TelemetryStep;
import com.jjx.ai.llmobservability.observation.annotation.TelemetryConversation;
import com.jjx.ai.llmobservability.observation.propagation.ContextPropagator;
import java.time.LocalDateTime;
import java.util.Collections;
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
    private final AgentTraceService agentTraceService;

    public ChatController(ChatService chatService,
                          ConversationMapper conversationMapper,
                          MessageMapper messageMapper,
                          AgentTraceService agentTraceService) {
        this.chatService = chatService;
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.agentTraceService = agentTraceService;
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




    /** 会话列表（分页，updatedAt 倒序；id 决胜避免同刻排序抖动） */
    @GetMapping("/conversations")
    public PageResult<ConversationEntity> listConversations(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        page = Math.max(page, 1);
        size = Math.min(Math.max(size, 1), 100);
        long total = conversationMapper.selectCount(new LambdaQueryWrapper<>());
        List<ConversationEntity> records = conversationMapper.selectList(
                new LambdaQueryWrapper<ConversationEntity>()
                        .orderByDesc(ConversationEntity::getUpdatedAt)
                        .orderByDesc(ConversationEntity::getId)
                        .last("LIMIT " + size + " OFFSET " + (long) (page - 1) * size));
        return new PageResult<>(total, records);
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



    /**
     * 会话消息（id 游标分页，按时间升序返回）。
     * <p>默认返回最近 limit 条；带 {@code beforeId} 返回更早一页（聊天「向上加载更早」语义），
     * 返回条数小于 limit 即已到最早。</p>
     */
    @GetMapping("/conversations/{conversationId}/messages")
    public List<MessageEntity> getMessages(@PathVariable String conversationId,
                                           @RequestParam(required = false) Long beforeId,
                                           @RequestParam(defaultValue = "50") int limit) {
        limit = Math.min(Math.max(limit, 1), 200);
        LambdaQueryWrapper<MessageEntity> qw = new LambdaQueryWrapper<MessageEntity>()
                .eq(MessageEntity::getConversationId, conversationId)
                .orderByDesc(MessageEntity::getId);
        if (beforeId != null) {
            qw.lt(MessageEntity::getId, beforeId);
        }
        List<MessageEntity> desc = messageMapper.selectList(qw.last("LIMIT " + limit));
        // 批量回填 traceId：历史消息无需先点“轨迹”即可显示“链路”入口
        List<Long> assistantIds = desc.stream()
                .filter(m -> "assistant".equals(m.getRole()) && m.getId() != null)
                .map(MessageEntity::getId)
                .toList();
        if (!assistantIds.isEmpty()) {
            Map<Long, String> traceIds = agentTraceService.traceIdsByMessageIds(assistantIds);
            desc.forEach(m -> m.setTraceId(traceIds.get(m.getId())));
        }
        Collections.reverse(desc);
        return desc;
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
