package com.agentframework.crosscutting.guard;

/**
 * 循环中断信号：守卫返回 {@link GuardDecision.BreakLoop} 时抛出，由工作流运行时接住并跳过回边。
 *
 * <p>它只用于把决策从深层挂载点（LLM / 工具）传递到运行时，不表示执行失败。</p>
 */
public class LoopBreakException extends RuntimeException {

    private final String reason;

    /**
     * @param reason 中断原因
     */
    public LoopBreakException(String reason) {
        super("循环中断：" + reason);
        this.reason = reason;
    }

    /** @return 中断原因 */
    public String reason() {
        return reason;
    }
}
