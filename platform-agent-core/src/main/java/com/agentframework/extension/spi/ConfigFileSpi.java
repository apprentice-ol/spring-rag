package com.agentframework.extension.spi;

import com.agentframework.extension.registry.Extension;
import com.agentframework.extension.registry.ExtensionMeta;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * 基于配置文件的 SPI 加载器。
 *
 * <p>配置格式（每行一条）：{@code <扩展点全限定名>#<注册名>=<实现类全限定名>}，
 * 例如 {@code com.acme.spi.PromptProvider#remote=com.acme.RemotePromptProvider}。</p>
 */
public final class ConfigFileSpi implements SpiLoader {

    private final Path configFile;
    private final ClassLoader classLoader;

    /**
     * @param configFile  配置文件路径
     * @param classLoader 加载实现类的类加载器，null 表示使用默认类加载器
     */
    public ConfigFileSpi(Path configFile, ClassLoader classLoader) {
        this.configFile = configFile;
        this.classLoader = classLoader == null ? ConfigFileSpi.class.getClassLoader() : classLoader;
    }

    @Override
    public String source() {
        return "config:" + configFile;
    }

    @Override
    public List<SpiEntry> load() {
        if (configFile == null || !Files.exists(configFile)) {
            return List.of();
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(configFile)) {
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("读取 SPI 配置失败：" + configFile, e);
        }
        List<SpiEntry> entries = new ArrayList<>();
        for (String key : properties.stringPropertyNames()) {
            // 只处理 "<扩展点>#<注册名>" 形式的键，忽略插件清单等其它配置项
            if (!key.contains("#")) {
                continue;
            }
            entries.add(instantiate(key, properties.getProperty(key)));
        }
        return List.copyOf(entries);
    }

    /**
     * 按配置项实例化实现。
     *
     * @param key     形如 {@code 扩展点#注册名} 的键
     * @param className 实现类全限定名
     * @return SPI 记录
     */
    private SpiEntry instantiate(String key, String className) {
        int separator = key.indexOf('#');
        String pointName = separator > 0 ? key.substring(0, separator) : null;
        String id = separator > 0 ? key.substring(separator + 1) : key;
        try {
            Class<?> point = pointName == null ? null : Class.forName(pointName, true, classLoader);
            Class<?> implClass = Class.forName(className.trim(), true, classLoader);
            Object impl = implClass.getDeclaredConstructor().newInstance();
            ExtensionMeta meta = point == null
                    ? ExtensionMeta.of(id, "unknown")
                    : ExtensionMeta.of(id, point.getSimpleName());
            if (impl instanceof Extension extension) {
                meta = extension.meta();
            } else if (point != null && !point.isInstance(impl)) {
                throw new IllegalStateException("实现 " + className + " 不是扩展点 " + pointName + " 的实例");
            }
            return new SpiEntry(meta, point, impl);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("实例化 SPI 实现失败：" + className, e);
        }
    }
}
