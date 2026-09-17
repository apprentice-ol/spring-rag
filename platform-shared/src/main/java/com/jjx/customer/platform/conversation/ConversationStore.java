package com.jjx.customer.platform.conversation;

/**
 * 会话存储（SPI）：编排层读写对话上下文的唯一入口。
 *
 * <p>方向：business（编排）依赖本接口；delivery 模块提供实现（sa_conversation / sa_message）。
 * 编排决策需要"历史上下文与先行对象"这类会话事实，但不需要知道它们存哪、怎么存。</p>
 */
public interface ConversationStore {

    /** 确保会话存在（首条消息以 firstQuestion 前 30 字作标题）。 */
    void ensureConversation(String conversationId, String firstQuestion);

    /** 追加一条用户消息，返回消息 id（内容为空返回 null）。 */
    Long appendUserMessage(String conversationId, String question);

    /** 最近 2 轮（4 条）历史消息拼成的 LLM 上下文前缀；无历史返回空串。 */
    String historyContext(String conversationId);

    /** 会话内用户消息条数（指代悬空判定：<=1 说明本轮是首条）。 */
    long userMessageCount(String conversationId);

    /** 最近 limit 条用户消息文本（换行拼接，新的在前）；无则空串。 */
    String recentUserText(String conversationId, int limit);
}
