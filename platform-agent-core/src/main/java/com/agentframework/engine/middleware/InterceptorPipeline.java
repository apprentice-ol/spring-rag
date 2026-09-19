package com.agentframework.engine.middleware;

import com.agentframework.crosscutting.interceptor.Interceptor;
import com.agentframework.crosscutting.interceptor.InterceptorContext;
import com.agentframework.crosscutting.interceptor.InterceptorPhase;
import com.agentframework.crosscutting.interceptor.Invocation;

/**
 * 拦截器管道门面：统一控制流程入口。
 */
public final class InterceptorPipeline {

    private final MiddlewarePipeline pipeline;

    /** @param pipeline 底层中间件管道 */
    public InterceptorPipeline(MiddlewarePipeline pipeline) {
        this.pipeline = pipeline;
    }

    /**
     * 注册拦截器。
     *
     * @param interceptor 拦截器实现
     * @return 当前门面
     */
    public InterceptorPipeline register(Interceptor interceptor) {
        pipeline.register(interceptor);
        return this;
    }

    /**
     * 执行拦截链。
     *
     * @param phase    挂载点
     * @param context  拦截器上下文
     * @param terminal 管道最内层调用
     * @param <T>      返回值类型
     * @return 调用结果
     * @throws Exception 调用失败时抛出
     */
    public <T> T execute(InterceptorPhase phase, InterceptorContext context, Invocation<T> terminal)
            throws Exception {
        return pipeline.intercept(phase, context, terminal);
    }
}
