package com.agentframework.definition.workflow;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流级 Prompt 默认值，节点级配置可覆盖。
 *
 * @param defaultProfile    默认 Prompt 配置档
 * @param defaultRenderer   默认渲染器名称
 * @param filterRefs        Prompt 阶段默认过滤器
 * @param guardRefs         Prompt 阶段默认守卫
 * @param variables         默认变量表
 */
public record PromptPolicy(
        String defaultProfile,
        String defaultRenderer,
        List<String> filterRefs,
        List<String> guardRefs,
        Map<String, Object> variables) {

    public PromptPolicy {
        filterRefs = List.copyOf(filterRefs == null ? List.of() : filterRefs);
        guardRefs = List.copyOf(guardRefs == null ? List.of() : guardRefs);
        variables = variables == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }

    /** @return 默认空策略 */
    public static PromptPolicy defaults() {
        return new PromptPolicy(null, null, null, null, null);
    }

    /**
     * @param defaultProfile 默认 Prompt 配置档
     * @return 指定配置档的策略
     */
    public static PromptPolicy of(String defaultProfile) {
        return new PromptPolicy(defaultProfile, null, null, null, null);
    }
}
