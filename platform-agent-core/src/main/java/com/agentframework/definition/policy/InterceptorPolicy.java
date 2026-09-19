package com.agentframework.definition.policy;

import java.util.List;

/**
 * 拦截器策略：声明 Agent / Workflow / Node / LLM / Tool 各挂载点外层的控制管道。
 *
 * @param refs    自定义拦截器名称列表
 * @param trace   三态：{@code null} 继承上层，{@code true} 启用，{@code false} 关闭
 * @param cache   三态：{@code null} 继承上层，{@code true} 启用，{@code false} 关闭
 * @param metrics 三态：{@code null} 继承上层，{@code true} 启用，{@code false} 关闭
 * @param mode    与继承集合的合并模式
 */
public record InterceptorPolicy(List<String> refs, Boolean trace, Boolean cache, Boolean metrics, MergeMode mode) {

    public InterceptorPolicy {
        refs = List.copyOf(refs == null ? List.of() : refs);
        mode = mode == null ? MergeMode.ADD : mode;
    }

    /** @return 默认策略：不额外声明，追踪 / 缓存 / 指标沿用上层设置 */
    public static InterceptorPolicy defaults() {
        return new InterceptorPolicy(List.of(), null, null, null, MergeMode.ADD);
    }

    /**
     * @param refs 自定义拦截器名称
     * @return 附带自定义拦截器的默认策略
     */
    public static InterceptorPolicy of(String... refs) {
        return new InterceptorPolicy(List.of(refs), null, null, null, MergeMode.ADD);
    }

    /** @return 关闭链路追踪后的策略 */
    public InterceptorPolicy withoutTrace() {
        return new InterceptorPolicy(refs, false, cache, metrics, mode);
    }

    /** @return 关闭缓存后的策略 */
    public InterceptorPolicy withoutCache() {
        return new InterceptorPolicy(refs, trace, false, metrics, mode);
    }

    /** @return 关闭指标采集后的策略 */
    public InterceptorPolicy withoutMetrics() {
        return new InterceptorPolicy(refs, trace, cache, false, mode);
    }

    /**
     * @param mode 合并模式
     * @return 覆盖合并模式后的策略
     */
    public InterceptorPolicy withMode(MergeMode mode) {
        return new InterceptorPolicy(refs, trace, cache, metrics, mode);
    }
}
