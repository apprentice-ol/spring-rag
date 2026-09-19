package com.agentframework.engine.middleware;

import com.agentframework.crosscutting.filter.Filter;
import com.agentframework.crosscutting.filter.FilterContext;
import com.agentframework.crosscutting.filter.FilterPhase;
import com.agentframework.crosscutting.guard.Guard;
import com.agentframework.crosscutting.guard.GuardChain;
import com.agentframework.crosscutting.guard.GuardContext;
import com.agentframework.crosscutting.guard.GuardDecision;
import com.agentframework.crosscutting.guard.GuardPhase;
import com.agentframework.crosscutting.interceptor.Interceptor;
import com.agentframework.crosscutting.interceptor.InterceptorChain;
import com.agentframework.crosscutting.interceptor.InterceptorContext;
import com.agentframework.crosscutting.interceptor.InterceptorPhase;
import com.agentframework.crosscutting.interceptor.Invocation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 默认中间件管道：注册表 + 按挂载点执行。
 *
 * <p>守卫与拦截器按自身 {@code order()} 排序；过滤器按输入类型过滤后再执行，避免类型不匹配。</p>
 */
public final class DefaultMiddlewarePipeline implements MiddlewarePipeline {

    private final Map<String, Guard> guards = new LinkedHashMap<>();
    private final Map<String, Filter<?, ?>> filters = new LinkedHashMap<>();
    private final Map<String, Interceptor> interceptors = new LinkedHashMap<>();

    @Override
    public GuardDecision guard(GuardPhase phase, GuardContext context) {
        GuardContext scoped = context.withPhase(phase);
        List<Guard> applicable;
        synchronized (guards) {
            applicable = new ArrayList<>(guards.values());
        }
        applicable.sort(Comparator.comparingInt(Guard::order));
        return new GuardChain(applicable).evaluate(scoped);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T filter(FilterPhase phase, T value, FilterContext context) {
        FilterContext scoped = context.withPhase(phase);
        List<Filter<?, ?>> applicable;
        synchronized (filters) {
            applicable = new ArrayList<>(filters.values());
        }
        applicable.sort(Comparator.comparingInt(filter -> filter.order()));
        Object current = value;
        for (Filter<?, ?> filter : applicable) {
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

    @Override
    public <T> T intercept(InterceptorPhase phase, InterceptorContext context, Invocation<T> terminal)
            throws Exception {
        InterceptorContext scoped = context.withPhase(phase);
        List<Interceptor> applicable;
        synchronized (interceptors) {
            applicable = new ArrayList<>(interceptors.values());
        }
        applicable.sort(Comparator.comparingInt(Interceptor::order));
        return InterceptorChain.of(applicable).execute(scoped, terminal);
    }

    @Override
    public MiddlewarePipeline register(Guard guard) {
        if (guard != null) {
            synchronized (guards) {
                guards.put(guard.name(), guard);
            }
        }
        return this;
    }

    @Override
    public MiddlewarePipeline register(Filter<?, ?> filter) {
        if (filter != null) {
            synchronized (filters) {
                filters.put(filter.name(), filter);
            }
        }
        return this;
    }

    @Override
    public MiddlewarePipeline register(Interceptor interceptor) {
        if (interceptor != null) {
            synchronized (interceptors) {
                interceptors.put(interceptor.name(), interceptor);
            }
        }
        return this;
    }

    /**
     * 移除守卫。
     *
     * @param name 守卫名称
     * @return 是否确实移除
     */
    public boolean unregisterGuard(String name) {
        synchronized (guards) {
            return guards.remove(name) != null;
        }
    }

    /**
     * 移除过滤器。
     *
     * @param name 过滤器名称
     * @return 是否确实移除
     */
    public boolean unregisterFilter(String name) {
        synchronized (filters) {
            return filters.remove(name) != null;
        }
    }

    /**
     * 移除拦截器。
     *
     * @param name 拦截器名称
     * @return 是否确实移除
     */
    public boolean unregisterInterceptor(String name) {
        synchronized (interceptors) {
            return interceptors.remove(name) != null;
        }
    }

    /**
     * 解析已注册的守卫。
     *
     * @param name 守卫名称
     * @return 守卫，未注册返回 null
     */
    public Guard guard(String name) {
        synchronized (guards) {
            return guards.get(name);
        }
    }

    /**
     * 解析已注册的过滤器。
     *
     * @param name 过滤器名称
     * @return 过滤器，未注册返回 null
     */
    public Filter<?, ?> filter(String name) {
        synchronized (filters) {
            return filters.get(name);
        }
    }

    /**
     * 解析已注册的拦截器。
     *
     * @param name 拦截器名称
     * @return 拦截器，未注册返回 null
     */
    public Interceptor interceptor(String name) {
        synchronized (interceptors) {
            return interceptors.get(name);
        }
    }

    @Override
    public List<Guard> guards() {
        synchronized (guards) {
            return List.copyOf(guards.values());
        }
    }

    @Override
    public List<Filter<?, ?>> filters() {
        synchronized (filters) {
            return List.copyOf(filters.values());
        }
    }

    @Override
    public List<Interceptor> interceptors() {
        synchronized (interceptors) {
            return List.copyOf(interceptors.values());
        }
    }
}
