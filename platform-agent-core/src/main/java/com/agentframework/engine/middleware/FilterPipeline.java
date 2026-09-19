package com.agentframework.engine.middleware;

import com.agentframework.crosscutting.filter.Filter;
import com.agentframework.crosscutting.filter.FilterContext;
import com.agentframework.crosscutting.filter.FilterPhase;

/**
 * 过滤器管道门面：统一数据变换入口。
 */
public final class FilterPipeline {

    private final MiddlewarePipeline pipeline;

    /** @param pipeline 底层中间件管道 */
    public FilterPipeline(MiddlewarePipeline pipeline) {
        this.pipeline = pipeline;
    }

    /**
     * 注册过滤器。
     *
     * @param filter 过滤器实现
     * @return 当前门面
     */
    public FilterPipeline register(Filter<?, ?> filter) {
        pipeline.register(filter);
        return this;
    }

    /**
     * 执行过滤链。
     *
     * @param phase   挂载点
     * @param value   待变换数据
     * @param context 过滤上下文
     * @param <T>     数据类型
     * @return 变换后的数据
     */
    public <T> T apply(FilterPhase phase, T value, FilterContext context) {
        return pipeline.filter(phase, value, context);
    }
}
