package com.agentframework.crosscutting.filter;

/**
 * 过滤器：只负责数据变换，不做流程控制，也不做放行决策。
 *
 * @param <I> 输入类型
 * @param <O> 输出类型
 */
public interface Filter<I, O> {

    /** @return 过滤器名称，用于策略引用与审计 */
    String name();

    /** @return 执行顺序，数值越小越先执行 */
    default int order() {
        return 100;
    }

    /**
     * 是否对该上下文生效。
     *
     * @param context 过滤上下文
     * @return 生效则返回 true
     */
    default boolean supports(FilterContext context) {
        return true;
    }

    /**
     * 变换数据。
     *
     * @param input   输入数据
     * @param context 过滤上下文
     * @return 变换后的数据，允许返回 null
     */
    O filter(I input, FilterContext context);
}
