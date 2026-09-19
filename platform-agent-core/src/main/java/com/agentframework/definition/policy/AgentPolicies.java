package com.agentframework.definition.policy;

/**
 * Agent 的完整策略面：工具、守卫、过滤器、拦截器与配额。
 *
 * <p>各字段为 null 时按各自默认值归一化，因此 {@code new AgentPolicies(null, null, null, null, null)}
 * 等价于一套“全部默认”的策略集合。</p>
 *
 * @param tool        工具策略
 * @param guard       守卫策略
 * @param filter      过滤器策略
 * @param interceptor 拦截器策略
 * @param quota       配额策略
 */
public record AgentPolicies(
        ToolPolicy tool,
        GuardPolicy guard,
        FilterPolicy filter,
        InterceptorPolicy interceptor,
        QuotaPolicy quota) {

    public AgentPolicies {
        tool = tool == null ? ToolPolicy.allowAll() : tool;
        guard = guard == null ? GuardPolicy.defaults() : guard;
        filter = filter == null ? FilterPolicy.defaults() : filter;
        interceptor = interceptor == null ? InterceptorPolicy.defaults() : interceptor;
        quota = quota == null ? QuotaPolicy.unlimited() : quota;
    }

    /** @return 全部使用默认值的策略集合 */
    public static AgentPolicies empty() {
        return new AgentPolicies(null, null, null, null, null);
    }

    /**
     * @param tool 工具策略
     * @return 仅覆盖工具策略的集合
     */
    public static AgentPolicies of(ToolPolicy tool) {
        return new AgentPolicies(tool, null, null, null, null);
    }

    /**
     * @param tool 新的工具策略
     * @return 覆盖工具策略后的集合
     */
    public AgentPolicies withTool(ToolPolicy tool) {
        return new AgentPolicies(tool, guard, filter, interceptor, quota);
    }

    /**
     * @param guard 新的守卫策略
     * @return 覆盖守卫策略后的集合
     */
    public AgentPolicies withGuard(GuardPolicy guard) {
        return new AgentPolicies(tool, guard, filter, interceptor, quota);
    }

    /**
     * @param filter 新的过滤器策略
     * @return 覆盖过滤器策略后的集合
     */
    public AgentPolicies withFilter(FilterPolicy filter) {
        return new AgentPolicies(tool, guard, filter, interceptor, quota);
    }

    /**
     * @param interceptor 新的拦截器策略
     * @return 覆盖拦截器策略后的集合
     */
    public AgentPolicies withInterceptor(InterceptorPolicy interceptor) {
        return new AgentPolicies(tool, guard, filter, interceptor, quota);
    }

    /**
     * @param quota 新的配额策略
     * @return 覆盖配额策略后的集合
     */
    public AgentPolicies withQuota(QuotaPolicy quota) {
        return new AgentPolicies(tool, guard, filter, interceptor, quota);
    }
}
