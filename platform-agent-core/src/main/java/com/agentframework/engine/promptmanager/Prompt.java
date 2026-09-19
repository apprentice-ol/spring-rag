package com.agentframework.engine.promptmanager;

import com.agentframework.definition.prompt.PromptScope;
import java.util.List;

/**
 * 运行期 Prompt 资产：由 {@code PromptProvider} 提供。
 *
 * @param id                资产 id
 * @param version           版本号
 * @param template          模板正文
 * @param scope             挂载范围
 * @param renderer          渲染器名称
 * @param requiredVariables 必填变量
 * @param filterRefs        绑定的过滤器
 * @param guardRefs         绑定的守卫
 */
public record Prompt(
        String id,
        String version,
        String template,
        PromptScope scope,
        String renderer,
        List<String> requiredVariables,
        List<String> filterRefs,
        List<String> guardRefs) {

    public Prompt {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Prompt id 不能为空");
        }
        version = version == null || version.isBlank() ? "latest" : version;
        template = template == null ? "" : template;
        scope = scope == null ? PromptScope.NODE : scope;
        renderer = renderer == null || renderer.isBlank() ? "template" : renderer;
        requiredVariables = List.copyOf(requiredVariables == null ? List.of() : requiredVariables);
        filterRefs = List.copyOf(filterRefs == null ? List.of() : filterRefs);
        guardRefs = List.copyOf(guardRefs == null ? List.of() : guardRefs);
    }

    /**
     * @param id       资产 id
     * @param version  版本号
     * @param template 模板正文
     * @return Prompt 资产
     */
    public static Prompt of(String id, String version, String template) {
        return new Prompt(id, version, template, null, null, null, null, null);
    }

    /** @return 资产唯一键，形如 {@code plan@1.0.0} */
    public String key() {
        return id + "@" + version;
    }
}
