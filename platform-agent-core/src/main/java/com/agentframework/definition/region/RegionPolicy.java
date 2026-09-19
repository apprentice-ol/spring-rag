package com.agentframework.definition.region;

import com.agentframework.definition.policy.PolicyBinding;
import com.agentframework.definition.policy.QuotaPolicy;
import java.util.List;

/**
 * Region 治理策略：区域内节点在该作用域继承的守卫、过滤器、拦截器与配额。
 *
 * @param guards       守卫绑定
 * @param filters      过滤器绑定
 * @param interceptors 拦截器绑定
 * @param quota        区域配额，null 表示不额外限制
 */
public record RegionPolicy(
        List<PolicyBinding> guards,
        List<PolicyBinding> filters,
        List<PolicyBinding> interceptors,
        QuotaPolicy quota) {

    /** 空策略。 */
    public static final RegionPolicy EMPTY = new RegionPolicy(null, null, null, null);

    public RegionPolicy {
        guards = List.copyOf(guards == null ? List.of() : guards);
        filters = List.copyOf(filters == null ? List.of() : filters);
        interceptors = List.copyOf(interceptors == null ? List.of() : interceptors);
    }

    /**
     * @param names 组件名
     * @return 无参数守卫绑定列表
     */
    public static List<PolicyBinding> bindings(String... names) {
        return java.util.Arrays.stream(names).map(PolicyBinding::of).toList();
    }

    /** @return 是否未声明任何治理内容 */
    public boolean isEmpty() {
        return guards.isEmpty() && filters.isEmpty() && interceptors.isEmpty() && quota == null;
    }

    /** @return 是否声明了配额 */
    public boolean hasQuota() {
        return quota != null;
    }
}
