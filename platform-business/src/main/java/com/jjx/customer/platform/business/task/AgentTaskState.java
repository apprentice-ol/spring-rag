package com.jjx.customer.platform.business.task;

import java.util.Map;

/**
 * 任务状态（业务侧自有契约）。
 *
 * <p><b>为什么要有 Task</b>：原设计用 {@code "ops-" + conversationId} 作引擎会话 id，
 * 把「对话容器」与「一次执行」焊死，于是四个模块各解释一遍"结束了没"——
 * 引擎看 COMPLETED、业务看 TTL、预算挂会话上、上下文跟引擎走。结果是：
 * 诊断出结论后追问，槽位与过程全丢（引擎会话被同 id 覆盖），
 * 而挂起中追问却完好——两种命运用户看不出来。</p>
 *
 * <p><b>本契约把两者拆开</b>：Task 是工作边界（一个待解决的目标，可跨多次 attempt），
 * 引擎会话是执行边界（一次 attempt 一个会话 id，互不覆盖）。
 * {@code concluded} 是本次新增的关键状态——"已出结论、等用户反应"，
 * 它让"出结论"不再等于"任务结束"。</p>
 *
 * @param taskId         任务标识（UUID）
 * @param conversationId 所属对话
 * @param agentId        agent 范式（如 ops_diagnose）
 * @param stage          挂起时所在节点
 * @param status         状态
 * @param slots          已确认业务槽位
 * @param summary        结论摘要（终态回填）
 * @param autonomyLevel  自主档位（L1/L2/L3；null = 缺省 L2）
 * @param chainTraceId   诊断链 traceId（跨 attempt 沿用）
 * @param attemptCount   已发起的 attempt 数（0 = 尚未跑过）
 */
public record AgentTaskState(String taskId, String conversationId, String agentId, String stage,
                             Status status, Map<String, String> slots, String summary,
                             String autonomyLevel, String chainTraceId, int attemptCount) {

    /** 任务状态机。 */
    public enum Status {
        /** 目标未达成，无进行中的 attempt。 */
        OPEN,
        /** 有进行中的 attempt（并发保护：同一 Task 同时只允许一个）。 */
        RUNNING,
        /** attempt 挂起，明确在等用户回答。 */
        SUSPENDED,
        /** 已出结论，等用户反应（追问 → 同 Task 开新 attempt）。 */
        CONCLUDED,
        /** 目标达成 / 用户结束。 */
        CLOSED,
        /** 放弃 / 过期。 */
        ABANDONED
    }

    /**
     * 引擎会话 id：一次 attempt 一个会话，天然互不覆盖。
     *
     * <p>对比旧规则 {@code "ops-" + conversationId}——同 id 反复 startSession 覆盖，
     * 每一次收尾都在销毁上一轮的过程记录。</p>
     *
     * @param taskId    任务标识
     * @param attemptNo attempt 序号（从 1 起）
     * @return 引擎会话 id
     */
    public static String attemptIdOf(String taskId, int attemptNo) {
        return "ops-" + taskId + "#" + attemptNo;
    }

    /** @return 当前 attempt 的引擎会话 id（尚无 attempt 时按第 1 次算） */
    public String currentAttemptId() {
        return attemptIdOf(taskId, Math.max(attemptCount, 1));
    }

    /** @return 该状态是否仍可沿用（下一轮消息继续挂在这个 Task 上） */
    public boolean reusable() {
        return status == Status.OPEN || status == Status.SUSPENDED || status == Status.CONCLUDED;
    }

}
