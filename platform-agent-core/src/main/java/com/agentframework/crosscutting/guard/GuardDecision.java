package com.agentframework.crosscutting.guard;

/**
 * 守卫决策：放行、拒绝、改写或要求人工审批。
 *
 * <p>决策与执行分离——守卫只给出结论，由管道决定后续处理。</p>
 */
public sealed interface GuardDecision
        permits GuardDecision.Allow, GuardDecision.Deny, GuardDecision.Transform, GuardDecision.AskApproval,
        GuardDecision.BreakLoop {

    /** @return 是否允许继续执行 */
    default boolean allowed() {
        return this instanceof Allow || this instanceof Transform;
    }

    /** @return 决策说明，用于审计与错误信息 */
    String reason();

    /** 放行。 */
    record Allow(String reason) implements GuardDecision {
    }

    /** 拒绝。 */
    record Deny(String reason) implements GuardDecision {
    }

    /** 改造载荷后放行（例如脱敏、截断）。 */
    record Transform(Object payload, String reason) implements GuardDecision {
    }

    /** 需要人工审批后才可继续。 */
    record AskApproval(String reason) implements GuardDecision {
    }

    /**
     * 中断当前循环：运行时跳过回边、沿前向边继续。
     *
     * <p>由循环守卫在达到迭代上限、收敛或成本超限时返回。</p>
     */
    record BreakLoop(String reason) implements GuardDecision {
    }

    /** @return 放行决策 */
    static GuardDecision allow() {
        return new Allow("allowed");
    }

    /**
     * @param reason 放行说明
     * @return 放行决策
     */
    static GuardDecision allow(String reason) {
        return new Allow(reason);
    }

    /**
     * @param reason 拒绝原因
     * @return 拒绝决策
     */
    static GuardDecision deny(String reason) {
        return new Deny(reason);
    }

    /**
     * @param payload 改造后的载荷
     * @param reason  改造原因
     * @return 改造决策
     */
    static GuardDecision transform(Object payload, String reason) {
        return new Transform(payload, reason);
    }

    /**
     * @param reason 审批原因
     * @return 待审批决策
     */
    static GuardDecision askApproval(String reason) {
        return new AskApproval(reason);
    }

    /**
     * @param reason 中断原因
     * @return 中断循环决策
     */
    static GuardDecision breakLoop(String reason) {
        return new BreakLoop(reason);
    }
}
