package com.jjx.customer.platform.agent.framework.node;

/**
 * 阶段内可见的预算视图（引擎托管总账，节点只读）。
 *
 * @param remainingLlmCalls 剩余 LLM 调用次数（&lt;0 = 不限）
 * @param deadlineMillis    截止时间（Long.MAX_VALUE = 不限）
 */
public record BudgetView(int remainingLlmCalls, long deadlineMillis) {

    public static BudgetView unlimited() {
        return new BudgetView(-1, Long.MAX_VALUE);
    }

    public boolean llmExhausted() {
        return remainingLlmCalls == 0;
    }

    public boolean timedOut() {
        return System.currentTimeMillis() > deadlineMillis;
    }

    public boolean exhausted() {
        return llmExhausted() || timedOut();
    }

    /** 记账一次 LLM 调用（返回新视图；节点用它驱动循环）。 */
    public BudgetView consumeLlmCall() {
        return remainingLlmCalls < 0 ? this : new BudgetView(Math.max(0, remainingLlmCalls - 1), deadlineMillis);
    }
}
