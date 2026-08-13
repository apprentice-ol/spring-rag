package com.nageoffer.ai.rag.chat.service.pipeline;

import com.nageoffer.ai.rag.chat.intent.IntentResult;
import com.nageoffer.ai.rag.chat.retrieval.MultiChannelRetrievalEngine;
import java.util.List;
import lombok.Builder;
import lombok.Data;

/**
 * 流式对话管道上下文。
 * <p>
 * 贯穿 StreamChatPipeline 各阶段的状态对象。
 * 设计为不可变输入 + 可变管道状态（由各阶段依次填充）。
 * </p>
 *
 * @see StreamChatPipeline
 */
@Data
@Builder
public class StreamChatContext {

    // ==================== 不可变输入 ====================

    /** 用户原始问题 */
    private final String question;

    /** 会话 ID（同一 ID 自动带上历史记忆） */
    private final String conversationId;

    // ==================== 可变管道状态（各阶段填充） ====================

    /** 历史消息列表（loadMemory 阶段填充） */
    private List<org.springframework.ai.chat.messages.Message> history;

    /** 意图分类结果（intentClassify 阶段填充） */
    private IntentResult intentResult;

    /** 多通道检索结果（retrieve 阶段填充） */
    private MultiChannelRetrievalEngine.RetrievalResult retrievalResult;
}
