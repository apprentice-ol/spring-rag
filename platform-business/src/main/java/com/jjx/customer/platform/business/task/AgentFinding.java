package com.jjx.customer.platform.business.task;

import java.util.List;

/**
 * 诊断主张：一次诊断产出的、**可被单独否定的**断言。
 *
 * <p>为什么不是"把结论存成一段文本"：文本无法局部否定。用户说"报文字段不对"时，
 * 系统只能整段重来；有了逐条主张，就能精确标掉被驳倒的那条，其余原样保留。</p>
 *
 * <p><b>只追加，不物理删除。</b>状态机区分两种"不再有效"——
 * {@code SUPERSEDED}（被新结论取代，正常演进）与 {@code RETRACTED}（被判定为错，否定），
 * 二者审计含义不同：前者是知识前进，后者是纠错。</p>
 *
 * @param findingId      主张标识
 * @param taskId         所属任务
 * @param conversationId 所属对话（查询便利，冗余自 task）
 * @param kind           类型
 * @param claim          断言正文
 * @param evidence       证据（指针式，非全文——用时可回查）
 * @param status         状态
 * @param attemptNo      由第几次 attempt 产出
 */
public record AgentFinding(String findingId, String taskId, String conversationId,
                           Kind kind, String claim, List<Evidence> evidence,
                           Status status, int attemptNo) {

    /** 主张类型（与四段式交付格式的段落对应）。 */
    public enum Kind {
        /** 根因断言（来自「排查结论」段）。 */
        ROOT_CAUSE,
        /** 字段/接口约束（从修正动作里提炼的规则性断言）。 */
        CONSTRAINT,
        /** 修正动作（来自「修正动作」段）。 */
        FIX,
        /** 风险提示（来自「风险提醒」段，逐条）。 */
        RISK,
        /** 问答型任务的答案（知识线复用同一契约时用）。 */
        ANSWER
    }

    /** 主张状态。 */
    public enum Status {
        /** 生效（参与上下文组装）。 */
        ACTIVE,
        /** 被新结论取代（正常演进，留档）。 */
        SUPERSEDED,
        /** 被判定为错（用户否定／新证据推翻，留档）。 */
        RETRACTED
    }

    /**
     * 证据条目：**指针 + 摘录**，不是全文。
     *
     * <p>设计上刻意不带原始日志/检索分片全文——那些可以按 {@code ref} 回查（traceId、时间窗、
     * 工具名都在槽位里），把全文塞进主张会让上下文随会话线性膨胀。</p>
     *
     * @param label  条目标签（如「接口」「请求报文」）
     * @param value  取值
     * @param source 来源说明（如「traceId … 精查命中 ERROR 级日志」）
     */
    public record Evidence(String label, String value, String source) {
    }

    /** 便捷构造：新抽取出的主张一律 ACTIVE。 */
    public static AgentFinding active(String findingId, String taskId, String conversationId,
                                      Kind kind, String claim, List<Evidence> evidence, int attemptNo) {
        return new AgentFinding(findingId, taskId, conversationId, kind, claim,
                evidence == null ? List.of() : List.copyOf(evidence), Status.ACTIVE, attemptNo);
    }
}
