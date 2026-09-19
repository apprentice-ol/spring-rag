package com.agentframework.crosscutting.interceptor;

/**
 * 拦截器：控制管道扩展点，负责流程控制（重试、超时、熔断、缓存、追踪、日志）。
 *
 * <p>拦截器不改变业务语义，只改变“怎么执行”。数据变换请用 {@code Filter}，放行决策请用 {@code Guard}。</p>
 */
public interface Interceptor {

    /** @return 拦截器名称 */
    String name();

    /** @return 执行顺序，数值越小越靠外层 */
    default int order() {
        return 100;
    }

    /**
     * 是否对该上下文生效。
     *
     * @param context 拦截器上下文
     * @return 生效返回 true
     */
    default boolean supports(InterceptorContext context) {
        return true;
    }

    /**
     * 环绕执行。
     *
     * @param invocation 被拦截的调用
     * @param context    拦截器上下文
     * @param <T>        返回值类型
     * @return 调用结果
     * @throws Exception 调用失败时抛出
     */
    <T> T intercept(Invocation<T> invocation, InterceptorContext context) throws Exception;
}
