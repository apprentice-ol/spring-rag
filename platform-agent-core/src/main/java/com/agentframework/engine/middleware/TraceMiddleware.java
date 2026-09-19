package com.agentframework.engine.middleware;

import com.agentframework.crosscutting.interceptor.Interceptor;
import com.agentframework.crosscutting.interceptor.Interceptors;
import com.agentframework.crosscutting.trace.Tracer;

/**
 * 追踪中间件：把追踪拦截器装配进管道的快捷入口。
 */
public final class TraceMiddleware {

    private TraceMiddleware() {
    }

    /**
     * @param tracer 追踪器
     * @return 追踪拦截器
     */
    public static Interceptor create(Tracer tracer) {
        return new Interceptors.Trace(tracer);
    }
}
