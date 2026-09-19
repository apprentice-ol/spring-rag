package com.agentframework.engine.middleware;

import com.agentframework.crosscutting.guard.Guard;
import com.agentframework.crosscutting.guard.GuardContext;
import com.agentframework.crosscutting.guard.GuardDecision;
import com.agentframework.crosscutting.guard.GuardPhase;

/**
 * 守卫管道门面：把守卫相关的调用收敛到一个入口，便于替换与测试。
 */
public final class GuardPipeline {

    private final MiddlewarePipeline pipeline;

    /** @param pipeline 底层中间件管道 */
    public GuardPipeline(MiddlewarePipeline pipeline) {
        this.pipeline = pipeline;
    }

    /**
     * 注册守卫。
     *
     * @param guard 守卫实现
     * @return 当前门面
     */
    public GuardPipeline register(Guard guard) {
        pipeline.register(guard);
        return this;
    }

    /**
     * 执行守卫并返回决策。
     *
     * @param phase   挂载点
     * @param context 守卫上下文
     * @return 决策结果
     */
    public GuardDecision evaluate(GuardPhase phase, GuardContext context) {
        return pipeline.guard(phase, context);
    }

    /**
     * 执行守卫并在拒绝时抛出异常。
     *
     * @param phase   挂载点
     * @param context 守卫上下文
     * @return 允许通过的载荷
     */
    public Object require(GuardPhase phase, GuardContext context) {
        return pipeline.requireGuard(phase, context);
    }
}
