package com.agentframework.definition.policy;

import java.util.List;

/**
 * 过滤器策略：声明生效的数据变换 Filter。
 *
 * @param refs     启用的过滤器名称列表；为空表示“注册即生效”，{@code *} 表示全部
 * @param disabled 显式禁用的过滤器名称，优先级高于 {@code refs}
 * @param mode     与继承集合的合并模式
 */
public record FilterPolicy(List<String> refs, List<String> disabled, MergeMode mode) {

    public FilterPolicy {
        refs = List.copyOf(refs == null ? List.of() : refs);
        disabled = List.copyOf(disabled == null ? List.of() : disabled);
        mode = mode == null ? MergeMode.ADD : mode;
    }

    /** @return 默认策略：不显式声明，也不禁用 */
    public static FilterPolicy defaults() {
        return new FilterPolicy(List.of(), List.of(), MergeMode.ADD);
    }

    /**
     * @param refs 启用的过滤器名称
     * @return 白名单式过滤策略
     */
    public static FilterPolicy of(String... refs) {
        return new FilterPolicy(List.of(refs), List.of(), MergeMode.ADD);
    }

    /** @return 显式启用全部已注册过滤器的策略 */
    public static FilterPolicy all() {
        return new FilterPolicy(List.of("*"), List.of(), MergeMode.ADD);
    }

    /**
     * 判断过滤器是否生效。
     *
     * @param filterName 过滤器名称
     * @return 生效返回 true
     */
    public boolean isEnabled(String filterName) {
        if (disabled.contains("*") || disabled.contains(filterName)) {
            return false;
        }
        return refs.isEmpty() || refs.contains("*") || refs.contains(filterName);
    }

    /**
     * 追加禁用项。
     *
     * @param names 需要禁用的过滤器名称
     * @return 新的策略实例
     */
    public FilterPolicy disable(String... names) {
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>(disabled);
        merged.addAll(List.of(names));
        return new FilterPolicy(refs, List.copyOf(merged), mode);
    }

    /**
     * @param mode 合并模式
     * @return 覆盖合并模式后的策略
     */
    public FilterPolicy withMode(MergeMode mode) {
        return new FilterPolicy(refs, disabled, mode);
    }
}
