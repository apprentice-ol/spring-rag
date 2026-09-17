package com.jjx.customer.platform.delivery.rest;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jjx.ai.llmobservability.observation.annotation.TelemetryConversation;
import com.jjx.ai.llmobservability.observation.annotation.TelemetryStep;
import com.jjx.ai.llmobservability.observation.propagation.ContextPropagator;
import com.jjx.customer.platform.business.trace.AgentTraceService;
import com.jjx.customer.platform.delivery.message.entity.ConversationEntity;
import com.jjx.customer.platform.delivery.message.entity.MessageEntity;
import com.jjx.customer.platform.delivery.message.mapper.ConversationMapper;
import com.jjx.customer.platform.delivery.message.mapper.MessageMapper;
import com.jjx.customer.platform.delivery.service.ChatService;
import com.jjx.customer.platform.delivery.runtime.ActiveStreamRegistry;
import com.jjx.customer.platform.common.dto.PageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 流式问答入口（SSE）+ 会话管理。 */
@Slf4j
@RestController
@RequestMapping("/chat")
public class ChatController {

    private final ChatService chatService;
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final AgentTraceService agentTraceService;
    private final ActiveStreamRegistry activeStreamRegistry;

    public ChatController(ChatService chatService,
                          ConversationMapper conversationMapper,
                          MessageMapper messageMapper,
                          AgentTraceService agentTraceService,
                          ActiveStreamRegistry activeStreamRegistry) {
        this.chatService = chatService;
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.agentTraceService = agentTraceService;
        this.activeStreamRegistry = activeStreamRegistry;
    }

    /**
     * 取消会话的活动流（「停止生成」）：dispose LLM 流（停 token 计费）+ 部分回答落库 + 关闭 emitter。
     * 会话无活动流时返回 cancelled=false（可能已自然结束，幂等无害）。
     */
    @PostMapping("/cancel")
    public Map<String, Object> cancel(@RequestParam String conversationId) {
        return Map.of("cancelled", activeStreamRegistry.cancel(conversationId));
    }



    @TelemetryStep("rag.chat")
    @TelemetryConversation
    @RequestMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE,
                    method = {RequestMethod.GET, RequestMethod.POST})
    public SseEmitter stream(@RequestParam String question,
                             @RequestParam(required = false) String conversationId,
                             @RequestParam(required = false) String agent,
                             @RequestParam(required = false) String agentChoice) {
        // 5 分钟超时
        SseEmitter emitter = new SseEmitter(300_000L);
        Thread.ofVirtual().start(ContextPropagator.wrap(() -> {
            try {
                chatService.streamChat(question, conversationId, agent, agentChoice, emitter);
            } catch (Exception e) {
                log.error("[ChatController] 流式处理异常", e);
                // 发标准 error 事件后正常 complete。不用 completeWithError：那会触发容器
                // error dispatch → 全局异常处理器往 text/event-stream 写 JSON →
                // HttpMediaTypeNotAcceptable 二次报错噪音；前端按 event 分支消费 error。
                try {
                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    emitter.send(SseEmitter.event().name("error").data(msg));
                    emitter.complete();
                } catch (Exception ignored) {
                    // 连接已断开/已 complete，忽略
                }
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
