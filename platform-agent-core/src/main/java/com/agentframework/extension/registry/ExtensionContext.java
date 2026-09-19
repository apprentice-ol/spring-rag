package com.agentframework.extension.registry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 扩展上下文：插件初始化时可见的宿主环境。
 *
 * @param pluginId 所属插件 id
 * @param config   插件配置
 * @param registry 扩展注册表，插件可在初始化阶段继续注册子扩展
 */
public record ExtensionContext(String pluginId, Map<String, Object> config, ExtensionRegistry registry) {

    public ExtensionContext {
        pluginId = pluginId == null || pluginId.isBlank() ? "host" : pluginId;
        config = config == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(config));
    }

    /**
     * @param registry 扩展注册表
     * @return 宿主自身使用的上下文
     */
    public static ExtensionContext host(ExtensionRegistry registry) {
        return new ExtensionContext("host", null, registry);
    }

    /**
     * @param registry 扩展注册表
     * @param pluginId 插件 id
     * @param config   插件配置
     * @return 插件上下文
     */
    public static ExtensionContext of(ExtensionRegistry registry, String pluginId, Map<String, Object> config) {
        return new ExtensionContext(pluginId, config, registry);
    }

    /**
     * 读取配置项。
     *
     * @param key          配置名
     * @param defaultValue 缺省值
     * @return 配置值
     */
    public String config(String key, String defaultValue) {
        Object value = config.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }
}
