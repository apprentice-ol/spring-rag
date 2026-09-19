package com.agentframework.crosscutting.interceptor;

/**
 * 被拦截的调用：拦截器通过调用 {@link #proceed()} 把控制权交回管道。
 *
 * @param <T> 返回值类型
 */
@FunctionalInterface
public interface Invocation<T> {

    /**
     * 继续执行后续拦截器或最终目标。
     *
     * @return 调用结果
     * @throws Exception 目标调用失败时抛出
     */
    T proceed() throws Exception;
}
