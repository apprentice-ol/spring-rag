package com.agentframework.extension.manifest;

import com.agentframework.extension.registry.ApiVersion;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件清单：描述插件身份、依赖、权限与隔离要求。
 *
 * @param id              插件 id
 * @param version         插件版本
 * @param apiVersion      依赖的扩展 API 版本
 * @param description     插件说明
 * @param extensionPoints 声明的扩展点名称
 * @param dependencies    依赖的其它插件 id
 * @param permissions     申请的权限（network / file / model / tool 等）
 * @param configSchema    配置项 schema：配置名 → 说明
 * @param isolation       隔离级别
 * @param entrypoint      入口类名，可为空
 */
public record PluginManifest(
        String id,
        String version,
        String apiVersion,
        String description,
        List<String> extensionPoints,
        List<String> dependencies,
        List<String> permissions,
        Map<String, String> configSchema,
        Isolation isolation,
        String entrypoint) {

    public PluginManifest {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("插件 id 不能为空");
        }
        version = version == null || version.isBlank() ? "1.0.0" : version;
        apiVersion = apiVersion == null || apiVersion.isBlank() ? ApiVersion.CURRENT.toString() : apiVersion;
        description = description == null ? "" : description;
        extensionPoints = List.copyOf(extensionPoints == null ? List.of() : extensionPoints);
        dependencies = List.copyOf(dependencies == null ? List.of() : dependencies);
        permissions = List.copyOf(permissions == null ? List.of() : permissions);
        configSchema = configSchema == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(configSchema));
        isolation = isolation == null ? Isolation.NONE : isolation;
    }

    /**
     * 构造最小清单。
     *
     * @param id      插件 id
     * @param version 插件版本
     * @return 插件清单
     */
    public static PluginManifest of(String id, String version) {
        return new PluginManifest(id, version, null, null, null, null, null, null, null, null);
    }

    /**
     * @param isolation 隔离级别
     * @return 覆盖隔离级别后的清单
     */
    public PluginManifest withIsolation(Isolation isolation) {
        return new PluginManifest(id, version, apiVersion, description, extensionPoints, dependencies,
                permissions, configSchema, isolation, entrypoint);
    }

    /**
     * @param permissions 申请的权限
     * @return 覆盖权限后的清单
     */
    public PluginManifest withPermissions(String... permissions) {
        return new PluginManifest(id, version, apiVersion, description, extensionPoints, dependencies,
                List.of(permissions), configSchema, isolation, entrypoint);
    }

    /** @return 插件唯一键，形如 {@code weather-plugin@1.0.0} */
    public String key() {
        return id + "@" + version;
    }
}
