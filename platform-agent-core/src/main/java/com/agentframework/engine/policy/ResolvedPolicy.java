package com.agentframework.engine.policy;

import com.agentframework.crosscutting.filter.Filter;
import com.agentframework.crosscutting.guard.Guard;
import com.agentframework.crosscutting.interceptor.Interceptor;
import com.agentframework.definition.policy.GuardPolicy;
import com.agentframework.definition.policy.QuotaPolicy;
import com.agentframework.definition.region.RegionLoop;
import java.util.List;
import java.util.Map;

/**
 * 解析结果：某一条作用域链上真正生效的组件集合。
 *
 * <p>不可变、已按 {@code order()} 排序，可安全在会话与并行分支之间共享。</p>
 *
 * @param scopeKind         末端作用域类别
 * @param scopeId           末端作用域 id
 * @param guards            生效的守卫，已排序
 * @param filters           生效的过滤器，已排序
 * @param interceptors      生效的拦截器，已排序
 * @param guardFailureModes 守卫名到失败语义的映射
 * @param auditTrail        解析轨迹，用于审计
 * @param regionId          所属 Region id，null 表示不属于任何 Region
 * @param paradigm          所属 Region 的范式标签，null 表示未声明
 * @param regionQuota       所属 Region 的配额策略，null 表示未限制
 * @param loopId            所属循环标识（等于 Region id），null 表示无显式循环
 * @param loop              循环声明，null 表示无显式循环
 */
public record ResolvedPolicy(
        PolicyScopeKind scopeKind,
        String scopeId,
        List<Guard> guards,
        List<Filter<?, ?>> filters,
        List<Interceptor> interceptors,
        Map<String, GuardPolicy.FailureMode> guardFailureModes,
        List<String> auditTrail,
        String regionId,
        String paradigm,
        QuotaPolicy regionQuota,
        String loopId,
        RegionLoop loop) {

    public ResolvedPolicy {
        guards = List.copyOf(guards == null ? List.of() : guards);
        filters = List.copyOf(filters == null ? List.of() : filters);
        interceptors = List.copyOf(interceptors == null ? List.of() : interceptors);
        guardFailureModes = Map.copyOf(guardFailureModes == null ? Map.of() : guardFailureModes);
        auditTrail = List.copyOf(auditTrail == null ? List.of() : auditTrail);
    }

    /**
     * @param guardName 守卫名
     * @return 失败语义，未声明时默认失败关闭
     */
    public GuardPolicy.FailureMode failureModeOf(String guardName) {
        return guardFailureModes.getOrDefault(guardName, GuardPolicy.FailureMode.DENY);
    }

    /** @return 是否没有任何生效组件 */
    public boolean isEmpty() {
        return guards.isEmpty() && filters.isEmpty() && interceptors.isEmpty();
    }

    /** @return 是否属于某个显式 Region */
    public boolean inRegion() {
        return regionId != null && !regionId.isBlank();
    }

    /**
     * 绑定 Region 元信息。
     *
     * @param regionId    区域 id
     * @param paradigm    范式标签
     * @param regionQuota 区域配额，可为 null
     * @return 绑定后的解析结果
     */
    public ResolvedPolicy withRegion(String regionId, String paradigm, QuotaPolicy regionQuota, RegionLoop loop) {
        return new ResolvedPolicy(scopeKind, scopeId, guards, filters, interceptors, guardFailureModes, auditTrail,
                regionId, paradigm, regionQuota, loop == null ? null : regionId, loop);
    }

    /** @return 是否属于一个显式声明的循环 */
    public boolean inLoop() {
        return loop != null;
    }
}
