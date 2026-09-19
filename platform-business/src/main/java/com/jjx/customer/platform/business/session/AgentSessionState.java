package com.jjx.customer.platform.business.session;

import java.util.List;
import java.util.Map;

/**
 * 澄清会话状态（业务侧自有契约，字段与旧内核 {@code AgentSessionState} 完全一致）。
 *
 * <p>持久化在 {@code sa_agent_session}（conversationId=sessionId、agentType=agentId 键映射）；
 * 与引擎级会话存储（{@code ops_engine_session}，cursor/挂起游标）是两个东西：
 * 本契约只承载「澄清状态机」（AWAITING_USER / TTL / 已确认槽位）。</p>
 *
 * @param sessionId     会话标识（= 对话 conversationId）
 * @param agentId       Agent 标识（如 ops_diagnose）
 * @param stage         挂起阶段（挂起节点 id 或阶段名）
 * @param status        状态（AWAITING_USER / RUNNING / DONE / EXPIRED）
 * @param slots         已确认槽位（键值都为字符串）
 * @param missingSlots  缺失槽位名清单
 * @param summary       会话结论（终态时回填）
 * @param autonomyLevel 会话自主档位（人在环中 P3：L1/L2/L3；null = 缺省 L2）
 * @param chainTraceId  诊断链 traceId（首轮生成、追问轮沿用；同一次诊断跨轮不断链）
 */
public record AgentSessionState(String sessionId, String agentId, String stage, Status status,
                                Map<String, String> slots, List<String> missingSlots, String summary,
                                String autonomyLevel, String chainTraceId) {

    /** 兼容旧构造（无档位：按缺省 L2 处理）。 */
    public AgentSessionState(String sessionId, String agentId, String stage, Status status,
                             Map<String, String> slots, List<String> missingSlots, String summary) {
        this(sessionId, agentId, stage, status, slots, missingSlots, summary, null, null);
    }

    /** 兼容构造（P3：带档位、不带链 id）。 */
    public AgentSessionState(String sessionId, String agentId, String stage, Status status,
                             Map<String, String> slots, List<String> missingSlots, String summary,
                             String autonomyLevel) {
        this(sessionId, agentId, stage, status, slots, missingSlots, summary, autonomyLevel, null);
    }

    /** 会话生命周期状态。 */
    public enum Status {
        /** 等待用户补充（挂起）。 */
        AWAITING_USER,
        /** 执行中。 */
        RUNNING,
        /** 已完成（终态）。 */
        DONE,
        /** 已过期（TTL 触发）。 */
        EXPIRED
    }
}
