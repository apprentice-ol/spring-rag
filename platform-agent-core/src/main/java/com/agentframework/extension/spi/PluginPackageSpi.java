package com.agentframework.extension.spi;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 基于插件包的 SPI 加载器。
 *
 * <p>插件包目录约定：{@code plugin.properties}（清单 + 扩展声明）、{@code classes/}（编译产物）、
 * {@code lib/*.jar}（依赖）。加载时使用独立 {@link URLClassLoader}，为后续隔离与热卸载留出空间。</p>
 */
public final class PluginPackageSpi implements SpiLoader {

    private final Path pluginRoot;

    /** @param pluginRoot 插件包根目录 */
    public PluginPackageSpi(Path pluginRoot) {
        this.pluginRoot = pluginRoot;
    }

    @Override
    public String source() {
        return "plugin:" + pluginRoot;
    }

    @Override
    public List<SpiEntry> load() {
        if (pluginRoot == null || !Files.isDirectory(pluginRoot)) {
            return List.of();
        }
        URLClassLoader classLoader = buildClassLoader();
        return new ConfigFileSpi(pluginRoot.resolve("plugin.properties"), classLoader).load();
    }

    /**
     * 构建插件专用类加载器。
     *
     * @return 包含 {@code classes/} 与 {@code lib/*.jar} 的类加载器
     */
    private URLClassLoader buildClassLoader() {
        List<URL> urls = new ArrayList<>();
        try {
            Path classes = pluginRoot.resolve("classes");
            if (Files.isDirectory(classes)) {
                urls.add(classes.toUri().toURL());
            }
            Path lib = pluginRoot.resolve("lib");
            if (Files.isDirectory(lib)) {
                try (Stream<Path> jars = Files.list(lib)) {
                    jars.filter(path -> path.toString().endsWith(".jar"))
                            .forEach(path -> {
                                try {
                                    urls.add(path.toUri().toURL());
                                } catch (IOException e) {
                                    throw new IllegalStateException("读取插件依赖失败：" + path, e);
                                }
                            });
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("构建插件类加载器失败：" + pluginRoot, e);
        }
        return new URLClassLoader(urls.toArray(URL[]::new), PluginPackageSpi.class.getClassLoader());
    }
}
