package com.jjx.customer.platform.agent.framework.spi;

/**
 * 执行事件发布口（Spring {@code ApplicationEventPublisher} 范式）。
 *
 * <p>由引擎在每次执行时创建（绑定当前 {@code ExecutionPlan}）并交给驱动；
 * 驱动在骨架阶段推进处发布，引擎转发给所有 {@link ExecutionListener}。</p>
 */
@FunctionalInterface
public interface ExecutionEvents {

    /**
     * @param phase  骨架阶段
     * @param detail 阶段上下文（如阶段名；无则 null）
     */
    void phase(ExecutionPhase phase, String detail);

    ExecutionEvents NOOP = (phase, detail) -> {
    };
}
