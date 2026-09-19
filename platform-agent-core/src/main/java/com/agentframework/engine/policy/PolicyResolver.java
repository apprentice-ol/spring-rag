package com.agentframework.engine.policy;

import com.agentframework.crosscutting.filter.Filter;
import com.agentframework.crosscutting.guard.Guard;
import com.agentframework.crosscutting.interceptor.Interceptor;
import com.agentframework.definition.policy.GuardPolicy;
import com.agentframework.definition.policy.MergeMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 策略解析器：把作用域链解析成真正生效的组件集合。
 *
 * <p>合并规则：默认 ADD（并集），REPLACE 丢弃继承的非强制集合，disable 做减集，
 * 强制组件不可移除，参数由最靠近执行单元的作用域覆盖。解析结果是纯函数输出，
 * 只依赖作用域链与目录版本。</p>
 */
public final class PolicyResolver {

    private final PolicyCatalog catalog;
    private final PromptLookup promptLookup;
    private final Map<FactoryKey, Object> factoryCache = new ConcurrentHashMap<>();

    /** @param catalog 组件目录 */
    public PolicyResolver(PolicyCatalog catalog) {
        this(catalog, PromptLookup.EMPTY);
    }

    /**
     * @param catalog      组件目录
     * @param promptLookup Prompt 资产查询，用于并入 Prompt 级引用
     */
    public PolicyResolver(PolicyCatalog catalog, PromptLookup promptLookup) {
        this.catalog = Objects.requireNonNull(catalog, "policy catalog is required");
        this.promptLookup = promptLookup == null ? PromptLookup.EMPTY : promptLookup;
    }

    /** @return Prompt 资产查询 */
    public PromptLookup promptLookup() {
        return promptLookup;
    }

    /** @return 目录版本号，可作为解析缓存的失效键 */
    public long catalogVersion() {
        return catalog.version();
    }

    /**
     * 解析一条作用域链。
     *
     * @param chain 作用域链，由外到内
     * @return 解析结果
     */
    public ResolvedPolicy resolve(PolicyScopeChain chain) {
        Accumulator guards = new Accumulator(PolicyKind.GUARD, chain);
        Accumulator filters = new Accumulator(PolicyKind.FILTER, chain);
        Accumulator interceptors = new Accumulator(PolicyKind.INTERCEPTOR, chain);

        List<Guard> guardList = new ArrayList<>();
        for (String name : guards.enabled()) {
            component(PolicyKind.GUARD, name, guards).ifPresent(value -> {
                if (value instanceof Guard guard) {
                    guardList.add(guard);
                }
            });
        }
        guardList.sort(Comparator.comparingInt(Guard::order));

        List<Filter<?, ?>> filterList = new ArrayList<>();
        for (String name : filters.enabled()) {
            component(PolicyKind.FILTER, name, filters).ifPresent(value -> {
                if (value instanceof Filter<?, ?> filter) {
                    filterList.add(filter);
                }
            });
        }
        filterList.sort(Comparator.comparingInt(Filter::order));

        List<Interceptor> interceptorList = new ArrayList<>();
        for (String name : interceptors.enabled()) {
            component(PolicyKind.INTERCEPTOR, name, interceptors).ifPresent(value -> {
                if (value instanceof Interceptor interceptor) {
                    interceptorList.add(interceptor);
                }
            });
        }
        interceptorList.sort(Comparator.comparingInt(Interceptor::order));

        Map<String, GuardPolicy.FailureMode> failureModes = new LinkedHashMap<>();
        guardList.forEach(guard -> failureModes.put(guard.name(), guards.failureMode()));

        List<String> audit = new ArrayList<>(guards.audit());
        audit.addAll(filters.audit());
        audit.addAll(interceptors.audit());

        PolicyScope innermost = chain.innermost();
        return new ResolvedPolicy(
                innermost == null ? PolicyScopeKind.GLOBAL : innermost.kind(),
                innermost == null ? "global" : innermost.id(),
                guardList, filterList, interceptorList, failureModes, audit, null, null, null, null, null);
    }

    /**
     * 解析单个组件：工厂按参数缓存实例，其余直接返回注册实例。
     *
     * @param kind      组件类别
     * @param name      注册名
     * @param acc       当前累加器，提供参数表
     * @return 组件实例
     */
    private java.util.Optional<Object> component(PolicyKind kind, String name, Accumulator acc) {
        return catalog.find(kind, name).map(entry -> {
            if (entry.factory() == null) {
                return entry.instance();
            }
            Map<String, Object> params = Map.copyOf(acc.params().getOrDefault(name, Map.of()));
            FactoryKey key = new FactoryKey(kind, name, params);
            return factoryCache.computeIfAbsent(key, ignored -> entry.create(params));
        });
    }

    /** 工厂缓存键：类别 + 名字 + 参数。 */
    private record FactoryKey(PolicyKind kind, String name, Map<String, Object> params) {
    }

    /** 单个组件类别的累加器。 */
    private final class Accumulator {

        private final PolicyKind kind;
        private final Set<String> enabled = new LinkedHashSet<>();
        private final Set<String> mandatory = new LinkedHashSet<>();
        private final Map<String, Map<String, Object>> params = new LinkedHashMap<>();
        private final List<String> audit = new ArrayList<>();
        private GuardPolicy.FailureMode failureMode = GuardPolicy.FailureMode.DENY;

        Accumulator(PolicyKind kind, PolicyScopeChain chain) {
            this.kind = kind;
            for (PolicyComponent component : catalog.components(kind)) {
                if (component.mandatory()) {
                    mandatory.add(component.name());
                    enabled.add(component.name());
                } else if (component.defaultOn()) {
                    enabled.add(component.name());
                }
            }
            chain.scopes().forEach(this::apply);
        }

        Set<String> enabled() {
            return enabled;
        }

        Map<String, Map<String, Object>> params() {
            return params;
        }

        List<String> audit() {
            return audit;
        }

        GuardPolicy.FailureMode failureMode() {
            return failureMode;
        }

        /** 合并一个作用域的声明。 */
        private void apply(PolicyScope scope) {
            if (kind == PolicyKind.GUARD && scope.guardFailureMode() != null) {
                failureMode = scope.guardFailureMode();
            }
            PolicyDirective directive = scope.directives().get(kind);
            if (directive == null || directive.isEmpty()) {
                return;
            }
            Set<String> enabling = expandRefs(directive.enable());
            Set<String> disabling = expandNames(directive.disable());
            if (directive.mode() == MergeMode.REPLACE) {
                enabled.clear();
                enabled.addAll(mandatory);
                enabled.addAll(enabling);
                audit.add(scope.kind() + ":" + scope.id() + " replace");
            } else {
                enabled.addAll(enabling);
                audit.add(scope.kind() + ":" + scope.id() + " enable=" + enabling);
            }
            if (!disabling.isEmpty()) {
                Set<String> removed = new LinkedHashSet<>(disabling);
                removed.removeAll(mandatory);
                enabled.removeAll(removed);
                audit.add(scope.kind() + ":" + scope.id() + " disable=" + removed);
            }
            enabled.addAll(mandatory);
            applyFlags(directive.flags());
            for (PolicyRef ref : directive.enable()) {
                if (ref.hasParams()) {
                    params.computeIfAbsent(ref.name(), ignored -> new LinkedHashMap<>()).putAll(ref.params());
                }
            }
        }

        /** 应用三态开关，仅拦截器类别有效。 */
        private void applyFlags(Map<String, Tri> flags) {
            if (kind != PolicyKind.INTERCEPTOR || flags.isEmpty()) {
                return;
            }
            flags.forEach((flag, tri) -> {
                if (tri == Tri.INHERIT || catalog.find(kind, flag).isEmpty()) {
                    return;
                }
                if (tri == Tri.ON) {
                    enabled.add(flag);
                } else if (!mandatory.contains(flag)) {
                    enabled.remove(flag);
                }
            });
        }

        /** @return 展开引用中的通配符 */
        private Set<String> expandRefs(List<PolicyRef> refs) {
            Set<String> names = new LinkedHashSet<>();
            for (PolicyRef ref : refs) {
                if ("*".equals(ref.name())) {
                    names.addAll(catalog.names(kind));
                } else {
                    names.add(ref.name());
                }
            }
            return names;
        }

        /** @return 展开名字列表中通配符 */
        private Set<String> expandNames(List<String> names) {
            Set<String> expanded = new LinkedHashSet<>();
            for (String name : names) {
                if ("*".equals(name)) {
                    expanded.addAll(catalog.names(kind));
                } else {
                    expanded.add(name);
                }
            }
            return expanded;
        }
    }
}
