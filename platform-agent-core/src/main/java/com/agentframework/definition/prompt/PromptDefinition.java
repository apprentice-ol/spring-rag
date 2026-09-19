package com.agentframework.definition.prompt;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Prompt 定义：可版本化的资产，而不是散落在代码里的字符串。
 *
 * <p>引擎按 id + version 通过 {@code PromptProvider} 取回资产，再依次渲染、过滤、守卫。</p>
 *
 * @param id                Prompt 标识
 * @param version           版本号，缺省为 {@link #LATEST}
 * @param scope             挂载范围
 * @param template          模板正文，支持 {@code {{变量}}} 占位
 * @param variablesSchema   变量名到类型的声明
 * @param requiredVariables 必填变量名
 * @param renderer          渲染器名称，缺省使用内置模板渲染器
 * @param filterRefs        该 Prompt 专属过滤器
 * @param guardRefs         该 Prompt 专属守卫
 * @param metadata          自定义元数据
 */
public record PromptDefinition(
        String id,
        String version,
        PromptScope scope,
        String template,
        Map<String, String> variablesSchema,
        List<String> requiredVariables,
        String renderer,
        List<String> filterRefs,
        List<String> guardRefs,
        Map<String, Object> metadata) {

    public static final String LATEST = "latest";

    public PromptDefinition {
        Objects.requireNonNull(id, "prompt id is required");
        version = version == null || version.isBlank() ? LATEST : version;
        scope = scope == null ? PromptScope.NODE : scope;
        Objects.requireNonNull(template, "prompt template is required");
        variablesSchema = variablesSchema == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(variablesSchema));
        requiredVariables = List.copyOf(requiredVariables == null ? List.of() : requiredVariables);
        filterRefs = List.copyOf(filterRefs == null ? List.of() : filterRefs);
        guardRefs = List.copyOf(guardRefs == null ? List.of() : guardRefs);
        metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    /**
     * @param id       Prompt 标识
     * @param version  版本号
     * @param template 模板正文
     * @return Prompt 定义
     */
    public static PromptDefinition of(String id, String version, String template) {
        return new PromptDefinition(id, version, null, template, null, null, null, null, null, null);
    }

    /**
     * @param id       Prompt 标识
     * @param template 模板正文
     * @return 版本为 latest 的 Prompt 定义
     */
    public static PromptDefinition template(String id, String template) {
        return of(id, LATEST, template);
    }

    /** @return 资产唯一键，形如 {@code plan@1.0.0} */
    public String key() {
        return id + "@" + version;
    }

    /**
     * @param scope 挂载范围
     * @return 覆盖范围后的定义
     */
    public PromptDefinition withScope(PromptScope scope) {
        return new PromptDefinition(id, version, scope, template, variablesSchema, requiredVariables,
                renderer, filterRefs, guardRefs, metadata);
    }

    /**
     * @param renderer 渲染器名称
     * @return 覆盖渲染器后的定义
     */
    public PromptDefinition withRenderer(String renderer) {
        return new PromptDefinition(id, version, scope, template, variablesSchema, requiredVariables,
                renderer, filterRefs, guardRefs, metadata);
    }

    /**
     * @param name     变量名
     * @param type     变量类型
     * @param required 是否必填
     * @return 追加变量声明后的定义
     */
    public PromptDefinition withVariable(String name, String type, boolean required) {
        Map<String, String> schema = new LinkedHashMap<>(variablesSchema);
        schema.put(name, type);
        LinkedHashSet<String> requiredNames = new LinkedHashSet<>(requiredVariables);
        if (required) {
            requiredNames.add(name);
        }
        return new PromptDefinition(id, version, scope, template, schema, List.copyOf(requiredNames),
                renderer, filterRefs, guardRefs, metadata);
    }

    /**
     * @param refs 追加的过滤器名称
     * @return 追加过滤器后的定义
     */
    public PromptDefinition withFilters(String... refs) {
        return new PromptDefinition(id, version, scope, template, variablesSchema, requiredVariables,
                renderer, merge(filterRefs, refs), guardRefs, metadata);
    }

    /**
     * @param refs 追加的守卫名称
     * @return 追加守卫后的定义
     */
    public PromptDefinition withGuards(String... refs) {
        return new PromptDefinition(id, version, scope, template, variablesSchema, requiredVariables,
                renderer, filterRefs, merge(guardRefs, refs), metadata);
    }

    /**
     * @param key   元数据键
     * @param value 元数据值
     * @return 追加元数据后的定义
     */
    public PromptDefinition withMetadata(String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(metadata);
        merged.put(key, value);
        return new PromptDefinition(id, version, scope, template, variablesSchema, requiredVariables,
                renderer, filterRefs, guardRefs, merged);
    }

    /** 合并去重后返回新的字符串列表。 */
    private static List<String> merge(List<String> base, String... extra) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(base);
        merged.addAll(List.of(extra));
        return List.copyOf(merged);
    }
}
