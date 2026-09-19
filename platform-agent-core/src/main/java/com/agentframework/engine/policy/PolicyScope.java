package com.agentframework.engine.policy;

import com.agentframework.definition.agent.AgentDefinition;
import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.node.NodeMeta;
import com.agentframework.definition.policy.AgentPolicies;
import com.agentframework.definition.policy.FilterPolicy;
import com.agentframework.definition.policy.GuardPolicy;
import com.agentframework.definition.policy.InterceptorPolicy;
import com.agentframework.definition.region.RegionDefinition;
import com.agentframework.definition.region.RegionPolicy;
import com.agentframework.definition.workflow.WorkflowDefinition;
import com.agentframework.engine.promptmanager.Prompt;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 一个策略作用域：作用域类别、id 与该作用域声明的指令。
 *
 * @param kind             作用域类别
 * @param id               作用域标识，例如 Agent key、Workflow key 或节点 id
 * @param directives       按组件类别索引的策略指令
 * @param guardFailureMode 守卫失败语义，null 表示继承
 */
public record PolicyScope(
        PolicyScopeKind kind,
        String id,
        Map<PolicyKind, PolicyDirective> directives,
        GuardPolicy.FailureMode guardFailureMode) {

    public PolicyScope {
        kind = kind == null ? PolicyScopeKind.GLOBAL : kind;
        id = id == null ? kind.name().toLowerCase(Locale.ROOT) : id;
        directives = directives == null ? Map.of() : Map.copyOf(directives);
    }

    /**
     * @param kind 组件类别
     * @return 该作用域针对该类别的指令
     */
    public Optional<PolicyDirective> directive(PolicyKind kind) {
        return Optional.ofNullable(directives.get(kind));
    }

    /**
     * @param kind       作用域类别
     * @param id         作用域标识
     * @param directives 策略指令
     * @return 作用域
     */
    public static PolicyScope of(PolicyScopeKind kind, String id, PolicyDirective... directives) {
        return new PolicyScope(kind, id, index(directives == null ? List.of() : List.of(directives)), null);
    }

    /** @return 全局作用域，本身不携带声明 */
    public static PolicyScope global() {
        return new PolicyScope(PolicyScopeKind.GLOBAL, "global", Map.of(), null);
    }

    /**
     * @param region Region 定义
     * @return Region 作用域
     */
    public static PolicyScope region(RegionDefinition region) {
        RegionPolicy policy = region.policy();
        List<PolicyDirective> directives = new ArrayList<>();
        if (!policy.guards().isEmpty()) {
            directives.add(PolicyDirective.enabling(PolicyKind.GUARD, bindings(policy.guards())));
        }
        if (!policy.filters().isEmpty()) {
            directives.add(PolicyDirective.enabling(PolicyKind.FILTER, bindings(policy.filters())));
        }
        if (!policy.interceptors().isEmpty()) {
            directives.add(PolicyDirective.enabling(PolicyKind.INTERCEPTOR, bindings(policy.interceptors())));
        }
        return new PolicyScope(PolicyScopeKind.REGION, region.id(), index(directives), null);
    }

    /**
     * @param definition Agent 定义
     * @return Agent 作用域
     */
    public static PolicyScope agent(AgentDefinition definition) {
        AgentPolicies policies = definition.policies();
        List<PolicyDirective> directives = new ArrayList<>();
        directives.add(guards(policies.guard()));
        directives.add(filters(policies.filter()));
        directives.add(interceptors(policies.interceptor()));
        return new PolicyScope(PolicyScopeKind.AGENT, definition.key(), index(directives),
                policies.guard().failureMode());
    }

    /**
     * @param definition 工作流定义
     * @return Workflow 作用域
     */
    public static PolicyScope workflow(WorkflowDefinition definition) {
        List<PolicyDirective> directives = new ArrayList<>();
        if (!definition.guardRefs().isEmpty()) {
            directives.add(enabling(PolicyKind.GUARD, definition.guardRefs()));
        }
        if (!definition.filterRefs().isEmpty()) {
            directives.add(enabling(PolicyKind.FILTER, definition.filterRefs()));
        }
        if (!definition.interceptorRefs().isEmpty()) {
            directives.add(enabling(PolicyKind.INTERCEPTOR, definition.interceptorRefs()));
        }
        return new PolicyScope(PolicyScopeKind.WORKFLOW, definition.key(), index(directives), null);
    }

    /**
     * @param node 节点定义
     * @return Node 作用域
     */
    public static PolicyScope node(NodeDefinition node) {
        return node(node, null);
    }

    /**
     * 构建节点作用域：节点自身的引用优先，Prompt 资产上的引用作为补充并入。
     *
     * @param node   节点定义
     * @param prompt Prompt 资产，可为 null
     * @return Node 作用域
     */
    public static PolicyScope node(NodeDefinition node, Prompt prompt) {
        NodeMeta meta = node.meta();
        List<String> guardNames = new ArrayList<>();
        List<String> filterNames = new ArrayList<>();
        if (meta != null) {
            guardNames.addAll(meta.guardRefs());
            filterNames.addAll(meta.filterRefs());
        }
        if (prompt != null) {
            prompt.guardRefs().stream().filter(ref -> !guardNames.contains(ref)).forEach(guardNames::add);
            prompt.filterRefs().stream().filter(ref -> !filterNames.contains(ref)).forEach(filterNames::add);
        }
        List<PolicyDirective> directives = new ArrayList<>();
        if (!guardNames.isEmpty()) {
            directives.add(enabling(PolicyKind.GUARD, guardNames));
        }
        if (!filterNames.isEmpty()) {
            directives.add(enabling(PolicyKind.FILTER, filterNames));
        }
        return new PolicyScope(PolicyScopeKind.NODE, node.id(), index(directives), null);
    }

    /** @return 名字列表转启用指令 */
    private static PolicyDirective enabling(PolicyKind kind, List<String> names) {
        List<PolicyRef> refs = new ArrayList<>(names.size());
        names.forEach(name -> refs.add(PolicyRef.of(name)));
        return PolicyDirective.enabling(kind, refs);
    }

    /** @return 策略绑定转引用列表 */
    private static List<PolicyRef> bindings(List<com.agentframework.definition.policy.PolicyBinding> bindings) {
        List<PolicyRef> refs = new ArrayList<>(bindings.size());
        bindings.forEach(binding -> refs.add(new PolicyRef(binding.name(), binding.params())));
        return refs;
    }

    /** @return 守卫策略转指令，未声明时返回 null */
    private static PolicyDirective guards(GuardPolicy policy) {
        if (policy == null || policy.refs().isEmpty()) {
            return null;
        }
        return enabling(PolicyKind.GUARD, policy.refs());
    }

    /** @return 过滤器策略转指令，未声明时返回 null */
    private static PolicyDirective filters(FilterPolicy policy) {
        if (policy == null || (policy.refs().isEmpty() && policy.disabled().isEmpty())) {
            return null;
        }
        List<PolicyRef> refs = new ArrayList<>(policy.refs().size());
        policy.refs().forEach(name -> refs.add(PolicyRef.of(name)));
        return PolicyDirective.of(PolicyKind.FILTER, refs, policy.disabled(), policy.mode());
    }

    /** @return 拦截器策略转指令，未声明时返回 null */
    private static PolicyDirective interceptors(InterceptorPolicy policy) {
        if (policy == null) {
            return null;
        }
        Map<String, Tri> flags = new LinkedHashMap<>();
        flags.put("trace", tri(policy.trace()));
        flags.put("cache", tri(policy.cache()));
        flags.put("metrics", tri(policy.metrics()));
        boolean declared = !policy.refs().isEmpty() || flags.containsValue(Tri.ON) || flags.containsValue(Tri.OFF);
        if (!declared) {
            return null;
        }
        List<PolicyRef> refs = new ArrayList<>(policy.refs().size());
        policy.refs().forEach(name -> refs.add(PolicyRef.of(name)));
        return new PolicyDirective(PolicyKind.INTERCEPTOR, refs, List.of(), policy.mode(), flags);
    }

    /** @return 布尔转三态 */
    private static Tri tri(Boolean value) {
        if (value == null) {
            return Tri.INHERIT;
        }
        return value ? Tri.ON : Tri.OFF;
    }

    /** @return 过滤空指令后按类别索引 */
    private static Map<PolicyKind, PolicyDirective> index(List<PolicyDirective> directives) {
        Map<PolicyKind, PolicyDirective> indexed = new EnumMap<>(PolicyKind.class);
        for (PolicyDirective directive : directives) {
            if (directive != null && !directive.isEmpty()) {
                indexed.put(directive.kind(), directive);
            }
        }
        return indexed;
    }
}
