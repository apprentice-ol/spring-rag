package com.jjx.customer.platform.agent.framework.session;

import java.util.List;
import java.util.Map;

/**
 * 一次 Agent 会话的跨轮状态（槽位 / 阶段 / 状态机 / 摘要）——会话契约的唯一模型。
 *
 * <p>读取侧：编排层在执行前 {@code findActive}（会话恢复必须先于路由）；
 * 写入侧：引擎在执行出口落库（CLARIFY → {@link Status#AWAITING_USER}，其余终态 → {@link Status#DONE}）。
 * 持久化实现由使用方承担（本仓 = {@code sa_agent_session}）。</p>
 *
 * @param sessionId    会话标识（本仓 = conversationId）
 * @param agentId      创建该会话的 Agent id（恢复时据此路由回原 Agent）
 * @param stage        当前阶段（槽位澄清 = {@code collect_slots}；阶段内追问 = 阶段名）
 * @param status       状态机
 * @param slots        已确认槽位（只含流程声明过的键）
 * @param missingSlots 尚缺的必填槽位（一次问齐用）
 * @param summary      阶段产出一句话摘要（恢复时拼进 user message，不持久化完整对话）
 */
public record AgentSessionState(String sessionId,
                                String agentId,
                                String stage,
                                Status status,
                                Map<String, String> slots,
                                List<String> missingSlots,
                                String summary) {

    /** 会话状态机：等待用户补充 → 运行中 → 完成 / 过期。 */
    public enum Status {
        AWAITING_USER, RUNNING, DONE, EXPIRED
    }

    public AgentSessionState {
        slots = slots == null ? Map.of() : Map.copyOf(slots);
        missingSlots = missingSlots == null ? List.of() : List.copyOf(missingSlots);
    }

    /** 追问中断：等待用户补充缺失槽位（引擎在 CLARIFY 出口构造）。 */
    public static AgentSessionState awaitingUser(String sessionId, String agentId, String stage,
                                                 Map<String, String> slots, List<String> missingSlots,
                                                 String summary) {
        return new AgentSessionState(sessionId, agentId, stage, Status.AWAITING_USER,
                slots, missingSlots, summary);
    }

    /** 占位运行中（编排层 claim 成功后）。 */
    public AgentSessionState running() {
        return new AgentSessionState(sessionId, agentId, stage, Status.RUNNING, slots, missingSlots, summary);
    }
}
