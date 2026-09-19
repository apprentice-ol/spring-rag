package com.agentframework.crosscutting.interceptor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 拦截器链：把拦截器折叠成嵌套调用，order 小的在外层。
 *
 * <p>执行模型与 Servlet Filter 一致：{@code before → proceed → after}。</p>
 */
public final class InterceptorChain {

    private final List<Interceptor> interceptors = new ArrayList<>();

    /** 创建空链。 */
    public InterceptorChain() {
    }

    /**
     * @param interceptors 初始拦截器集合
     * @return 构建好的链
     */
    public static InterceptorChain of(List<Interceptor> interceptors) {
        InterceptorChain chain = new InterceptorChain();
        if (interceptors != null) {
            interceptors.forEach(chain::add);
        }
        return chain;
    }

    /**
     * 追加拦截器。
     *
     * @param interceptor 拦截器实现
     * @return 当前链
     */
    public InterceptorChain add(Interceptor interceptor) {
        if (interceptor != null) {
            interceptors.add(interceptor);
            interceptors.sort(Comparator.comparingInt(Interceptor::order));
        }
        return this;
    }

    /** @return 链上的拦截器快照 */
    public List<Interceptor> interceptors() {
        return List.copyOf(interceptors);
    }

    /**
     * 执行拦截链。
     *
     * @param context  拦截器上下文
     * @param terminal 管道最内层的真实调用
     * @param <T>      返回值类型
     * @return 调用结果
     * @throws Exception 调用或拦截器失败时抛出
     */
    public <T> T execute(InterceptorContext context, Invocation<T> terminal) throws Exception {
        List<Interceptor> applicable = interceptors.stream()
                .filter(interceptor -> interceptor.supports(context))
                .toList();
        Invocation<T> current = terminal;
        for (int i = applicable.size() - 1; i >= 0; i--) {
            Interceptor interceptor = applicable.get(i);
            Invocation<T> next = current;
            current = () -> interceptor.intercept(next, context);
        }
        return current.proceed();
    }
}
