package com.jjx.customer.platform.agent.framework.session;

import java.util.Optional;

/**
 * 会话存储后端契约：跨轮会话状态的读写（持久化实现由使用方提供，本仓 = {@code sa_agent_session}）。
 *
 * <p>两侧共用同一契约，避免"编排层一套、引擎一套"：</p>
 * <ul>
 *   <li>编排层：{@link #findActive} + {@link #claim}——会话恢复必须先于路由，占位保证并发双请求只有一个继续；</li>
 *   <li>引擎：{@link #saveAwaitingUser}（CLARIFY 出口）/ {@link #complete}（终态出口）。</li>
 * </ul>
 */
public interface SessionStore {

    /** 活动会话（AWAITING_USER 且未过期）；过期实现自行置 EXPIRED 并返回 empty。 */
    Optional<AgentSessionState> findActive(String sessionId);

    /** 乐观占位：AWAITING_USER → RUNNING。true = 抢占成功（并发双请求只有一个继续恢复）。 */
    boolean claim(String sessionId);

    /** 追问中断落库（AWAITING_USER；无记录则插入）。 */
    void saveAwaitingUser(AgentSessionState state);

    /** 标记完成（DONE）。 */
    void complete(String sessionId);

    /** 未装配会话后端时的默认实现：无状态（引擎不读不写会话）。 */
    SessionStore NOOP = new SessionStore() {
        @Override
        public Optional<AgentSessionState> findActive(String sessionId) {
            return Optional.empty();
        }

        @Override
        public boolean claim(String sessionId) {
            return false;
        }

        @Override
        public void saveAwaitingUser(AgentSessionState state) {
            // 无状态：不落库
        }

        @Override
        public void complete(String sessionId) {
            // 无状态：不落库
        }
    };
}
