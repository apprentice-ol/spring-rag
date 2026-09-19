package com.agentframework.engine.policy;

import com.agentframework.crosscutting.filter.Filter;
import com.agentframework.crosscutting.guard.Guard;
import com.agentframework.crosscutting.interceptor.Interceptor;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 组件目录：内核与治理策略之间的唯一装配点。
 *
 * <p>目录把“注册”和“生效”分开：{@link Activation#DEFAULT_ON} 与
 * {@link Activation#MANDATORY} 组件默认进入解析结果，{@link Activation#DEFAULT_OFF}
 * 组件只在被显式引用时生效。注册顺序被保留，用于等价组件之间的稳定排序。</p>
 */
public final class PolicyCatalog {

    private final Map<PolicyKind, Map<String, PolicyComponent>> components = new EnumMap<>(PolicyKind.class);
    private final AtomicLong version = new AtomicLong();

    /** 创建空目录。 */
    public PolicyCatalog() {
        for (PolicyKind kind : PolicyKind.values()) {
            components.put(kind, new LinkedHashMap<>());
        }
    }

    /**
     * 注册守卫。
     *
     * @param name       注册名
     * @param activation 激活语义
     * @param guard      守卫实现
     * @return 当前目录
     */
    public PolicyCatalog registerGuard(String name, Activation activation, Guard guard) {
        return put(new PolicyComponent(PolicyKind.GUARD, name, activation, guard, null));
    }

    /**
     * 注册过滤器。
     *
     * @param name       注册名
     * @param activation 激活语义
     * @param filter     过滤器实现
     * @return 当前目录
     */
    public PolicyCatalog registerFilter(String name, Activation activation, Filter<?, ?> filter) {
        return put(new PolicyComponent(PolicyKind.FILTER, name, activation, filter, null));
    }

    /**
     * 注册拦截器。
     *
     * @param name        注册名
     * @param activation  激活语义
     * @param interceptor 拦截器实现
     * @return 当前目录
     */
    public PolicyCatalog registerInterceptor(String name, Activation activation, Interceptor interceptor) {
        return put(new PolicyComponent(PolicyKind.INTERCEPTOR, name, activation, interceptor, null));
    }

    /**
     * 注册参数化组件工厂。
     *
     * @param kind       组件类别
     * @param name       注册名
     * @param activation 激活语义
     * @param factory    工厂
     * @return 当前目录
     */
    public PolicyCatalog registerFactory(PolicyKind kind, String name, Activation activation, PolicyFactory factory) {
        if (factory == null) {
            throw new IllegalArgumentException("policy factory is required for '" + name + "'");
        }
        return put(new PolicyComponent(kind, name, activation, null, factory));
    }

    /**
     * @param kind 组件类别
     * @param name 注册名
     * @return 目录条目
     */
    public Optional<PolicyComponent> find(PolicyKind kind, String name) {
        if (kind == null || name == null) {
            return Optional.empty();
        }
        synchronized (components) {
            return Optional.ofNullable(components.get(kind).get(name));
        }
    }

    /**
     * @param kind 组件类别
     * @return 该类别全部条目，保持注册顺序
     */
    public List<PolicyComponent> components(PolicyKind kind) {
        if (kind == null) {
            return List.of();
        }
        synchronized (components) {
            return List.copyOf(components.get(kind).values());
        }
    }

    /**
     * @param kind 组件类别
     * @return 全部注册名，保持注册顺序
     */
    public Set<String> names(PolicyKind kind) {
        Set<String> names = new LinkedHashSet<>();
        components(kind).forEach(component -> names.add(component.name()));
        return names;
    }

    /**
     * 为拼写错误给出候选名：前缀匹配优先，其次编辑距离不超过 2。
     *
     * @param kind 组件类别
     * @param name 待解析的名字
     * @return 候选名列表，可能为空
     */
    public List<String> suggest(PolicyKind kind, String name) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        String needle = name.toLowerCase(Locale.ROOT);
        List<String> candidates = new ArrayList<>();
        for (String candidate : names(kind)) {
            String lower = candidate.toLowerCase(Locale.ROOT);
            if (lower.startsWith(needle) || needle.startsWith(lower) || editDistance(lower, needle) <= 2) {
                candidates.add(candidate);
            }
        }
        return List.copyOf(candidates);
    }

    /** @return 目录版本号，每次注册递增 */
    public long version() {
        return version.get();
    }

    /** @return 全部条目数量 */
    public int size() {
        return components.values().stream().mapToInt(Map::size).sum();
    }

    /**
     * 写入条目并校验实现类型与类别匹配。
     *
     * @param component 目录条目
     * @return 当前目录
     */
    private PolicyCatalog put(PolicyComponent component) {
        Object instance = component.instance();
        if (instance != null && !expectedType(component.kind()).isInstance(instance)) {
            throw new IllegalArgumentException("component '" + component.name() + "' is not a "
                    + component.kind() + " implementation: " + instance.getClass().getName());
        }
        synchronized (components) {
            components.get(component.kind()).put(component.name(), component);
        }
        version.incrementAndGet();
        return this;
    }

    /** @return 类别对应的实现接口 */
    private Class<?> expectedType(PolicyKind kind) {
        return switch (kind) {
            case GUARD -> Guard.class;
            case FILTER -> Filter.class;
            case INTERCEPTOR -> Interceptor.class;
        };
    }

    /** @return 两个字符串的编辑距离 */
    private int editDistance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }
}
