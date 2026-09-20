package com.jjx.customer.platform.delivery.message;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * 会话/消息持久化（{@link ConversationStore} 的实现）：sa_conversation/sa_message 的读写路径。
 * <p>编排层通过 {@link ConversationStore} 契约读取会话事实（历史上下文、先行对象），不接触表结构与 SQL；
 * {@code saveMessage} 是交付侧落库入口，供 SseDeliveryPort 使用。</p>
 */
@Component
@RequiredArgsConstructor
public class ChatMessageWriter implements ConversationStore {

    /** 消息角色字面量（表里存的就是 user/assistant）。 */
    private static final String USER_ROLE = "user";

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
        return saveMessage(conversationId, USER_ROLE, question);
    }

    @Override
    public long userMessageCount(String conversationId) {
        Long count = messageMapper.selectCount(Wrappers.lambdaQuery(MessageEntity.class)
                .eq(MessageEntity::getConversationId, conversationId)
                .eq(MessageEntity::getRole, USER_ROLE));
        return count == null ? 0L : count;
    }

    @Override
    public String recentUserText(String conversationId, int limit) {
        return messageMapper.selectList(Wrappers.lambdaQuery(MessageEntity.class)
                        .select(MessageEntity::getContent)
                        .eq(MessageEntity::getConversationId, conversationId)
                        .eq(MessageEntity::getRole, USER_ROLE)
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

    /**
     * 最近若干轮历史消息，拼接为 LLM 上下文前缀。无历史时返回空串。
     *
     * <p><b>按"第 N 条 user 消息"定界，不是按条数 LIMIT。</b>对话里并非每条用户消息都有助手回复
     * （用户点了停止生成、落库失败、空检索降级都可能留下半轮），按 {@code LIMIT 轮数×2} 取会在这种
     * 时候多带一条悬空消息、少带一整轮历史。这里改成先定位第 {@code rounds} 条 user 消息作下界、
     * 再取区间内的全部消息——中间缺没缺回复，拿到的都是干净的 N 轮。</p>
     *
     * <p>上界由调用方给（{@link HistorySpec#beforeMessageId()}）：编排在入口已把本轮问题落了库，
     * 不排除它，窗口里混进的就是当轮问题，不叫历史。</p>
     */
    @Override
    public String historyContext(String conversationId, HistorySpec spec) {
        Long floorId = floorIdOf(selectUserIdsBefore(conversationId, spec));
        if (floorId == null) {
            // 上界之前没有任何用户消息 = 本轮是会话首条，没有历史
            return Strings.EMPTY;
        }
        return render(selectWindow(conversationId, spec, floorId), spec.totalChars());
    }

    /** 第 1 段查询：严格早于上界的最近 {@code rounds} 条用户消息（倒序，只取 id）。 */
    private List<MessageEntity> selectUserIdsBefore(String conversationId, HistorySpec spec) {
        LambdaQueryWrapper<MessageEntity> query = Wrappers.lambdaQuery(MessageEntity.class)
                .select(MessageEntity::getId)
                .eq(MessageEntity::getConversationId, conversationId)
                .eq(MessageEntity::getRole, USER_ROLE)
                .orderByDesc(MessageEntity::getId)
                .last("LIMIT " + spec.rounds());
        if (spec.beforeMessageId() != null) {
            query.lt(MessageEntity::getId, spec.beforeMessageId());
        }
        return messageMapper.selectList(query);
    }

    /** 第 2 段查询：{@code [floorId, beforeMessageId)} 区间内的全部消息，升序 = 对话顺序。 */
    private List<MessageEntity> selectWindow(String conversationId, HistorySpec spec, long floorId) {
        LambdaQueryWrapper<MessageEntity> query = Wrappers.lambdaQuery(MessageEntity.class)
                .eq(MessageEntity::getConversationId, conversationId)
                .ge(MessageEntity::getId, floorId)
                .orderByAsc(MessageEntity::getId);
        if (spec.beforeMessageId() != null) {
            query.lt(MessageEntity::getId, spec.beforeMessageId());
        }
        return messageMapper.selectList(query);
    }

    /**
     * 窗口下界 = 候选里<b>最早</b>那条用户消息的 id（候选是倒序的，故取末位）。
     *
     * <p>包内可见的纯函数：这是取数逻辑里唯一"错了会静默答错"的判定（重了会多带一轮、
     * 轻了会少带一轮，都不报错），值得单测钉住。</p>
     *
     * @param descendingUsers 倒序的用户消息（可只含 id）；无候选返回 null（= 无历史）
     */
    static Long floorIdOf(List<MessageEntity> descendingUsers) {
        return descendingUsers == null || descendingUsers.isEmpty()
                ? null
                : descendingUsers.get(descendingUsers.size() - 1).getId();
    }

    /** 历史块里每条消息之间的分隔。 */
    private static final String MESSAGE_SEPARATOR = "\n\n";

    /** 截断标注：写明"截过"，而不是丢一个让模型自己猜的省略号。 */
    private static final String TRUNCATION_NOTE = "……（此条过长已截断）";

    /**
     * 渲染历史：按<b>总字符预算</b>从最新往回装。包内可见的纯函数（单测见 ChatMessageWriterTest）。
     *
     * <p><b>为什么不是"每条截 N 字"。</b>同一个窗口要服务两种用途：查询改写拿它当<b>线索</b>
     * （认出"它"指谁，截短反而干净），而生成/闲聊拿它当<b>操作对象</b>（翻译、总结上一轮回复，
     * 截断就等于把用户的活干一半）。按条切只能迁就前者——实测一条 703 字的书单回答被 500 字上限
     * 砍在第 4 条中间，模型只好如实回一句"原文被截断了，翻译到此为止"。</p>
     *
     * <p><b>规则</b>：从最新往回装，装得下就整条保留；<b>只有最新一条</b>允许截断（否则窗口为空），
     * 并带上明确的截断标注；更早的装不下就<b>整条丢弃</b>——留半句话比不留更糟，
     * 模型会拿半截内容当真去操作。</p>
     */
    static String render(List<MessageEntity> messages, int totalChars) {
        if (messages == null || messages.isEmpty()) {
            return Strings.EMPTY;
        }
        Deque<String> kept = new ArrayDeque<>(messages.size());
        int remaining = totalChars;
        for (int i = messages.size() - 1; i >= 0; i--) {
            MessageEntity message = messages.get(i);
            String prefix = (USER_ROLE.equals(message.getRole()) ? "用户" : "助手") + "：";
            String content = message.getContent() == null ? "" : message.getContent();
            int cost = prefix.length() + content.length() + MESSAGE_SEPARATOR.length();
            if (cost <= remaining) {
                kept.addFirst(prefix + content);
                remaining -= cost;
                continue;
            }
            if (kept.isEmpty()) {
                // 最新一条本身就超预算：只能截它（截了窗口才有内容），并把"截过"写明
                int keep = Math.max(0, remaining - prefix.length() - TRUNCATION_NOTE.length());
                kept.addFirst(prefix + content.substring(0, Math.min(keep, content.length())) + TRUNCATION_NOTE);
            }
            break; // 更早的一律不进窗口：预算已耗尽，整条丢弃
        }
        if (kept.isEmpty()) {
            return Strings.EMPTY;
        }
        StringBuilder sb = new StringBuilder("历史对话：\n");
        for (String line : kept) {
            sb.append(line).append(MESSAGE_SEPARATOR);
        }
        return sb.toString();
    }
}
