package com.agentframework.crosscutting.guard;

/**
 * 守卫：策略决策扩展点，只回答“能不能做”，不负责执行。
 *
 * <p>实现应为无状态且线程安全；有状态逻辑请放入槽位或外部存储。</p>
 */
public interface Guard {

    /** @return 守卫名称，用于策略引用与审计 */
    String name();

    /** @return 执行顺序，数值越小越先执行 */
    default int order() {
        return 100;
    }

    /**
     * 该守卫是否关心此挂载点。
     *
     * @param context 守卫上下文
     * @return 关心则返回 true
     */
    default boolean supports(GuardContext context) {
        return true;
    }

    /**
     * 做出决策。
     *
     * @param context 守卫上下文
     * @return 决策结果，不允许返回 null
     */
    GuardDecision check(GuardContext context);
}
