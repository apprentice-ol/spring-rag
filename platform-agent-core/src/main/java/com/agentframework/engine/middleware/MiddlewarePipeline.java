package com.agentframework.engine.middleware;

import com.agentframework.crosscutting.filter.Filter;
import com.agentframework.crosscutting.filter.FilterContext;
import com.agentframework.crosscutting.filter.FilterPhase;
import com.agentframework.crosscutting.guard.Guard;
import com.agentframework.crosscutting.guard.GuardChain;
import com.agentframework.crosscutting.guard.GuardContext;
import com.agentframework.crosscutting.guard.GuardDecision;
import com.agentframework.crosscutting.guard.GuardDeniedException;
import com.agentframework.crosscutting.guard.LoopBreakException;
import com.agentframework.crosscutting.guard.GuardPhase;
import com.agentframework.crosscutting.interceptor.Interceptor;
import com.agentframework.crosscutting.interceptor.InterceptorChain;
import com.agentframework.crosscutting.interceptor.InterceptorContext;
import com.agentframework.crosscutting.interceptor.InterceptorPhase;
import com.agentframework.crosscutting.interceptor.Invocation;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;

/**
 * 中间件管道：把守卫、过滤器、拦截器统一编排成一条执行管线。
 *
 * <p>三者职责严格区分：Guard 做决策、Filter 改数据、Interceptor 控流程。</p>
 */
public interface MiddlewarePipeline {

    /**
     * 执行守卫链。
     *
     * @param phase   挂载点
     * @param context 守卫上下文
     * @return 守卫决策
     */
    GuardDecision guard(GuardPhase phase, GuardContext context);

    /**
     * 使用指定守卫集合执行守卫链，供策略解析结果直接落地。
     *
     * @param phase   挂载点
     * @param context 守卫上下文
     * @param guards  已解析的守卫集合
     * @return 守卫决策
     */
    default GuardDecision guard(GuardPhase phase, GuardContext context, List<Guard> guards) {
        GuardContext scoped = context.withPhase(phase);
        return new GuardChain(guards == null ? List.of() : guards).evaluate(scoped);
    }

    /**
     * 执行守卫链并在拒绝时抛出异常。
     *
     * @param phase   挂载点
     * @param context 守卫上下文
     * @return 守卫允许的载荷（{@code Transform} 决策会返回改写后的载荷）
     * @throws GuardDeniedException 守卫拒绝或要求人工审批时抛出
     */
    default Object requireGuard(GuardPhase phase, GuardContext context) {
        GuardDecision decision = guard(phase, context);
        return switch (decision) {
            case GuardDecision.Allow ignored -> context.payload();
            case GuardDecision.Transform transform -> transform.payload();
            case GuardDecision.Deny deny -> throw new GuardDeniedException("guard-chain", phase, deny.reason());
            case GuardDecision.AskApproval ask ->
                    throw new GuardDeniedException("guard-chain", phase, "需要人工审批：" + ask.reason());
            case GuardDecision.BreakLoop breakLoop ->
                    throw new LoopBreakException(breakLoop.reason());
        };
    }

    /**
     * 执行过滤链。
     *
     * @param phase   挂载点
     * @param value   待变换的数据
     * @param context 过滤上下文
     * @param <T>     数据类型
     * @return 变换后的数据
     */
    <T> T filter(FilterPhase phase, T value, FilterContext context);

    /**
     * 使用指定过滤器集合执行过滤链。
     *
     * @param phase   挂载点
     * @param value   待变换的数据
     * @param context 过滤上下文
     * @param filters 已解析的过滤器集合
     * @param <T>     数据类型
     * @return 变换后的数据
     */
    @SuppressWarnings("unchecked")
    default <T> T filter(FilterPhase phase, T value, FilterContext context, List<Filter<?, ?>> filters) {
        FilterContext scoped = context.withPhase(phase);
        List<Filter<?, ?>> ordered = new ArrayList<>(filters == null ? List.of() : filters);
        ordered.sort(Comparator.comparingInt(Filter::order));
        Object current = value;
        for (Filter<?, ?> filter : ordered) {
            if (!filter.supports(scoped)) {
                continue;
            }
            Class<?> inputType = FilterTypes.inputType(filter);
            if (current != null && !inputType.isInstance(current)) {
                continue;
            }
            current = ((Filter<Object, Object>) filter).filter(current, scoped);
        }
        return (T) current;
    }

    /**
     * 执行拦截链。
     *
     * @param phase    挂载点
     * @param context  拦截器上下文
     * @param terminal 管道最内层的真实调用
     * @param <T>      返回值类型
     * @return 调用结果
     * @throws Exception 调用失败时抛出
     */
    <T> T intercept(InterceptorPhase phase, InterceptorContext context, Invocation<T> terminal) throws Exception;

    /**
     * 使用指定拦截器集合执行拦截链。
     *
     * @param phase        挂载点
     * @param context      拦截器上下文
     * @param interceptors 已解析的拦截器集合
     * @param terminal     管道最内层的真实调用
     * @param <T>          返回值类型
     * @return 调用结果
     * @throws Exception 调用失败时抛出
     */
    default <T> T intercept(InterceptorPhase phase, InterceptorContext context, List<Interceptor> interceptors,
            Invocation<T> terminal) throws Exception {
        InterceptorContext scoped = context.withPhase(phase);
        List<Interceptor> ordered = new ArrayList<>(interceptors == null ? List.of() : interceptors);
        ordered.sort(Comparator.comparingInt(Interceptor::order));
        return InterceptorChain.of(ordered).execute(scoped, terminal);
    }

    /**
     * 注册守卫。
     *
     * @param guard 守卫实现
     * @return 当前管道
     */
    MiddlewarePipeline register(Guard guard);

    /**
     * 注册过滤器。
     *
     * @param filter 过滤器实现
     * @return 当前管道
     */
    MiddlewarePipeline register(Filter<?, ?> filter);

    /**
     * 注册拦截器。
     *
     * @param interceptor 拦截器实现
     * @return 当前管道
     */
    MiddlewarePipeline register(Interceptor interceptor);

    /** @return 已注册的守卫 */
    List<Guard> guards();

    /** @return 已注册的过滤器 */
    List<Filter<?, ?>> filters();

    /** @return 已注册的拦截器 */
    List<Interceptor> interceptors();
}
