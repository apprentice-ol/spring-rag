package com.jjx.customer.platform.delivery.message;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jjx.customer.platform.conversation.ConversationStore;
import com.jjx.customer.platform.delivery.message.entity.ConversationEntity;
import com.jjx.customer.platform.delivery.message.entity.MessageEntity;
import com.jjx.customer.platform.delivery.message.mapper.ConversationMapper;
import com.jjx.customer.platform.delivery.message.mapper.MessageMapper;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.util.Strings;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 会话/消息持久化（{@link ConversationStore} 的实现）：sa_conversation/sa_message 的读写路径。
 * <p>编排层通过 {@link ConversationStore} 契约读取会话事实（历史上下文、先行对象），不接触表结构与 SQL；
 * {@code saveMessage} 是交付侧落库入口，供 SseDeliveryPort 使用。</p>
 */
@Component
@RequiredArgsConstructor
public class ChatMessageWriter implements ConversationStore {

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;

    /**
     * 确保会话存在并刷新 updated_at。
     * <p>热路径（已存在）走单语句 UPDATE 免取整行；不存在再插入，并发首条消息的
     * check-then-insert 竞态由 conversation_id 唯一约束兜底（DuplicateKeyException 幂等忽略）。
     */
    @Override
    public void ensureConversation(String conversationId, String firstQuestion) {
        int updated = conversationMapper.update(null,
                Wrappers.lambdaUpdate(ConversationEntity.class)
                        .eq(ConversationEntity::getConversationId, conversationId)
                        .set(ConversationEntity::getUpdatedAt, LocalDateTime.now()));
        if (updated > 0) {
            return;
        }
        ConversationEntity conversationEntity = new ConversationEntity();
        conversationEntity.setConversationId(conversationId);
        // 用首条问题前 30 字作为标题
        String title = firstQuestion == null ? "新对话" : firstQuestion.trim();
        if (title.length() > 30) {
            title = title.substring(0, 30);
        }
        conversationEntity.setTitle(title);
        conversationEntity.setCreatedAt(LocalDateTime.now());
        conversationEntity.setUpdatedAt(LocalDateTime.now());
        try {
            conversationMapper.insert(conversationEntity);
        } catch (DuplicateKeyException e) {
            // 并发首条消息：另一请求已插入，幂等
        }
    }

    @Override
    public Long appendUserMessage(String conversationId, String question) {
        return saveMessage(conversationId, "user", question);
    }

    @Override
    public long userMessageCount(String conversationId) {
        Long count = messageMapper.selectCount(Wrappers.lambdaQuery(MessageEntity.class)
                .eq(MessageEntity::getConversationId, conversationId)
                .eq(MessageEntity::getRole, "user"));
        return count == null ? 0L : count;
    }

    @Override
    public String recentUserText(String conversationId, int limit) {
        return messageMapper.selectList(Wrappers.lambdaQuery(MessageEntity.class)
                        .select(MessageEntity::getContent)
                        .eq(MessageEntity::getConversationId, conversationId)
                        .eq(MessageEntity::getRole, "user")
                        .orderByDesc(MessageEntity::getId)
                        .last("LIMIT " + Math.max(1, limit)))
                .stream()
                .map(MessageEntity::getContent)
                .reduce("", (a, b) -> a + "\n" + (b == null ? "" : b));
    }

    /** 保存一条消息并返回其 id（内容为空返回 null，不落库）。 */
    public Long saveMessage(String conversationId, String role, String content) {
        return saveMessage(conversationId, role, content, null);
    }

    /** 同上，带引用溯源 JSON（仅 RAG assistant 消息非空）。会话 updated_at 由 ensureConversation 统一刷新。 */
    public Long saveMessage(String conversationId, String role, String content, String citationsJson) {
        return saveMessage(conversationId, role, content, citationsJson, null);
    }

    /**
     * 同上，带交互卡片载荷（澄清/决策卡片的结构化 JSON）。
     *
     * <p>卡片只活在 SSE 事件里的话，刷新页面就没了——正文文本还原不出结构（问题/选项/来源角标）。
     * 随消息落库后，历史接口把 JSON 一并返回，前端即可重新渲染同样的卡片。</p>
     */
    public Long saveMessage(String conversationId, String role, String content, String citationsJson,
            String clarifyJson) {
        if (content == null || content.isBlank()) {
            return null;
        }
        MessageEntity messageEntity = new MessageEntity();
        messageEntity.setConversationId(conversationId);
        messageEntity.setRole(role);
        messageEntity.setContent(content);
        messageEntity.setCitations(citationsJson);
        messageEntity.setClarify(clarifyJson);
        messageEntity.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(messageEntity);
        return messageEntity.getId();
    }

    /** 加载最近 2 轮（4 条）历史消息，拼接为 LLM 上下文前缀。无历史时返回空串。 */
    @Override
    public String historyContext(String conversationId) {
        List<MessageEntity> recent = messageMapper.selectList(
                Wrappers.lambdaQuery(MessageEntity.class)
                        .eq(MessageEntity::getConversationId, conversationId)
                        .orderByDesc(MessageEntity::getCreatedAt)
                        // 同秒多条消息时以 id 决胜，保证多轮改写上下文顺序稳定
                        .orderByDesc(MessageEntity::getId)
                        .last("LIMIT 4"));
        if (recent == null || recent.isEmpty() || recent.size() < 2) {
            return Strings.EMPTY;
        }
        Collections.reverse(recent);
        StringBuilder sb = new StringBuilder("历史对话：\n");
        for (MessageEntity messageEntity : recent) {
            String role = "user".equals(messageEntity.getRole()) ? "用户" : "助手";
            String c = messageEntity.getContent() == null ? "" : messageEntity.getContent();
            if (c.length() > 500) {
                c = c.substring(0, 500) + "...";
            }
            sb.append(role).append("：").append(c).append("\n\n");
        }
        return sb.toString();
    }
}
