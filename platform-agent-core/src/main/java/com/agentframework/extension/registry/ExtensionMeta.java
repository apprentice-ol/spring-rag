package com.agentframework.extension.registry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 扩展元信息：注册表与插件体系识别一个扩展的最小信息集。
 *
 * @param id              扩展 id
 * @param version         扩展版本
 * @param apiVersion      依赖的扩展 API 版本
 * @param type            扩展点名称，取值见 {@code SpiTypes}
 * @param description     说明
 * @param attributes      自定义属性
 */
public record ExtensionMeta(
        String id,
        String version,
        String apiVersion,
        String type,
        String description,
        Map<String, Object> attributes) {

    public ExtensionMeta {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("扩展 id 不能为空");
        }
        version = version == null || version.isBlank() ? "1.0.0" : version;
        apiVersion = apiVersion == null || apiVersion.isBlank() ? ApiVersion.CURRENT.toString() : apiVersion;
        type = type == null || type.isBlank() ? "unknown" : type;
        description = description == null ? "" : description;
        attributes = attributes == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    /**
     * @param id   扩展 id
     * @param type 扩展点名称
     * @return 使用默认版本与说明的元信息
     */
    public static ExtensionMeta of(String id, String type) {
        return new ExtensionMeta(id, null, null, type, null, null);
    }

    /**
     * @param id      扩展 id
     * @param version 扩展版本
     * @param type    扩展点名称
     * @return 指定版本的元信息
     */
    public static ExtensionMeta of(String id, String version, String type) {
        return new ExtensionMeta(id, version, null, type, null, null);
    }
}
