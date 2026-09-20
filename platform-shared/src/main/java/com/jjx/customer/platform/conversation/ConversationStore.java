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

    /**
     * 最近若干轮历史消息拼成的 LLM 上下文前缀；无历史返回空串。
     *
     * <p><b>取数规格由调用方给</b>（轮数、截断、上界）：多轮上下文是编排决策，
     * 不该埋在存储实现里写死——历史要同时喂给查询改写与答案生成，两边窗口是否一致
     * 是编排层要能看见的事。</p>
     *
     * @param conversationId 会话 ID
     * @param spec           取数规格（见 {@link HistorySpec}）
     * @return 形如 {@code "历史对话：\n用户：…\n\n助手：…\n\n"}；无历史返回空串
     */
    String historyContext(String conversationId, HistorySpec spec);

    /** 会话内用户消息条数（指代悬空判定：<=1 说明本轮是首条）。 */
    long userMessageCount(String conversationId);

    /** 最近 limit 条用户消息文本（换行拼接，新的在前）；无则空串。 */
    String recentUserText(String conversationId, int limit);

    /**
     * 取历史的规格。
     *
     * <p><b>{@code beforeMessageId} 必给，不是可选优化</b>：编排在入口就把本轮问题落了库
     * （{@code ChatOrchestrator} 读历史时当轮消息已在表里）。不排除它，"最近 3 轮"拿到的
     * 就是"当轮 + 2 轮 + 一条悬空消息"，而且当轮问题还会和改写 prompt 末尾的「当前问题：」
     * 重复出现两次——历史窗口里混进当轮问题，是"历史"这个概念本身的退化。</p>
     *
     * @param beforeMessageId 只取 id 严格小于它的消息（传 appendUserMessage 的本轮返回值）；null = 不设上界
     * @param rounds          轮数（一轮 = user + assistant 各一条）
     * @param totalChars      <b>总预算</b>（不是单条上限）——见下
     */
    record HistorySpec(Long beforeMessageId, int rounds, int totalChars) {

        /** 轮数与预算收敛到正数：配置写 0/负数时不该变成"取不到历史"或"输出全空"。 */
        public HistorySpec {
            rounds = Math.max(1, rounds);
            totalChars = Math.max(1, totalChars);
        }

        /**
         * @param beforeMessageId 上界（见上）
         * @param rounds          轮数
         * @param totalChars      历史总字符预算；装不下时从最早的整条丢弃，只有最新一条允许截断
         */
        public static HistorySpec of(Long beforeMessageId, int rounds, int totalChars) {
            return new HistorySpec(beforeMessageId, rounds, totalChars);
        }
    }
}
