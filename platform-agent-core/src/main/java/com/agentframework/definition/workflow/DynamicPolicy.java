package com.agentframework.definition.workflow;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 动态路由白名单：声明哪些节点可以自主选路，以及允许的目标。
 *
 * <p>白名单里的目标必须是该节点的声明出边——图仍是可能性的唯一来源，
 * 动态只是在图允许的范围内做选择。</p>
 *
 * @param allowedTargets 节点 id 到允许目标列表的映射
 */
public record DynamicPolicy(Map<String, List<String>> allowedTargets) {

    /** 空策略：没有任何节点可以自主选路。 */
    public static final DynamicPolicy NONE = new DynamicPolicy(null);

    public DynamicPolicy {
        if (allowedTargets == null || allowedTargets.isEmpty()) {
            allowedTargets = Map.of();
        } else {
            Map<String, List<String>> copied = new LinkedHashMap<>();
            allowedTargets.forEach((nodeId, targets) -> {
                if (nodeId != null && !nodeId.isBlank()) {
                    copied.put(nodeId, List.copyOf(targets == null ? List.of() : targets));
                }
            });
            allowedTargets = Collections.unmodifiableMap(copied);
        }
    }

    /**
     * @param allowedTargets 节点 id 到允许目标的映射
     * @return 动态策略
     */
    public static DynamicPolicy of(Map<String, List<String>> allowedTargets) {
        return new DynamicPolicy(allowedTargets);
    }

    /** @return 是否没有任何动态路由声明 */
    public boolean isEmpty() {
        return allowedTargets.isEmpty();
    }

    /** @return 声明了动态路由的节点 id */
    public java.util.Set<String> nodes() {
        return allowedTargets.keySet();
    }

    /**
     * @param nodeId 节点 id
     * @return 该节点允许的目标列表，未声明时为空
     */
    public List<String> targetsOf(String nodeId) {
        return allowedTargets.getOrDefault(nodeId, List.of());
    }

    /**
     * @param nodeId 节点 id
     * @param target 目标节点 id
     * @return 是否允许该动态跳转
     */
    public boolean allows(String nodeId, String target) {
        return nodeId != null && target != null && targetsOf(nodeId).contains(target);
    }

    /**
     * @param nodeId  节点 id
     * @param targets 允许的目标
     * @return 追加声明后的策略
     */
    public DynamicPolicy with(String nodeId, List<String> targets) {
        Map<String, List<String>> merged = new LinkedHashMap<>(allowedTargets);
        merged.put(nodeId, List.copyOf(targets == null ? List.of() : targets));
        return new DynamicPolicy(merged);
    }
}
