package com.agentframework.extension.manifest;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 插件清单读取器：解析 {@code plugin.properties}。
 *
 * <p>清单示例：</p>
 * <pre>
 * plugin.id=weather-plugin
 * plugin.version=1.0.0
 * plugin.apiVersion=1.0.0
 * plugin.isolation=PROCESS
 * plugin.permissions=network,tool
 * plugin.extensionPoints=ToolProvider
 * plugin.dependencies=geo-plugin@1.0.0
 * plugin.entrypoint=com.acme.WeatherPlugin
 * plugin.config.city=默认城市
 * </pre>
 */
public final class PluginManifestReader {

    /** 清单文件名。 */
    public static final String FILE_NAME = "plugin.properties";

    private PluginManifestReader() {
    }

    /**
     * 读取插件目录下的清单。
     *
     * @param pluginRoot 插件根目录
     * @return 插件清单
     * @throws IllegalStateException 清单不存在或无法解析时抛出
     */
    public static PluginManifest read(Path pluginRoot) {
        Path file = pluginRoot.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            throw new IllegalStateException("插件目录缺少 " + FILE_NAME + "：" + pluginRoot);
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            // 使用 UTF-8 读取，保证中文配置值不会被按 ISO-8859-1 解析成乱码
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("读取插件清单失败：" + file, e);
        }
        return fromProperties(properties);
    }

    /**
     * 由属性表构建清单。
     *
     * @param properties 属性表
     * @return 插件清单
     */
    public static PluginManifest fromProperties(Properties properties) {
        String id = properties.getProperty("plugin.id");
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("插件清单缺少 plugin.id");
        }
        Map<String, String> configSchema = new LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) {
            if (name.startsWith("plugin.config.")) {
                configSchema.put(name.substring("plugin.config.".length()), properties.getProperty(name));
            }
        }
        Isolation isolation;
        try {
            isolation = Isolation.valueOf(properties.getProperty("plugin.isolation", "NONE").trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("非法的插件隔离级别：" + properties.getProperty("plugin.isolation"), e);
        }
        return new PluginManifest(
                id,
                properties.getProperty("plugin.version"),
                properties.getProperty("plugin.apiVersion"),
                properties.getProperty("plugin.description"),
                split(properties.getProperty("plugin.extensionPoints")),
                split(properties.getProperty("plugin.dependencies")),
                split(properties.getProperty("plugin.permissions")),
                configSchema,
                isolation,
                properties.getProperty("plugin.entrypoint"));
    }

    /**
     * 逗号分隔字符串转列表。
     *
     * @param value 原始字符串
     * @return 去空白后的列表
     */
    private static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                items.add(trimmed);
            }
        }
        return items;
    }
}
