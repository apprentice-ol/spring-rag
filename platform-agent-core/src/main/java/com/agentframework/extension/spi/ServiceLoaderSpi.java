package com.agentframework.extension.spi;

import com.agentframework.extension.registry.Extension;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * 基于 JDK {@link ServiceLoader} 的 SPI 加载器。
 *
 * <p>读取 {@code META-INF/services/com.agentframework.extension.registry.Extension} 中声明的实现。</p>
 */
public final class ServiceLoaderSpi implements SpiLoader {

    private final ClassLoader classLoader;

    /** 使用线程上下文类加载器。 */
    public ServiceLoaderSpi() {
        this(Thread.currentThread().getContextClassLoader());
    }

    /** @param classLoader 指定类加载器 */
    public ServiceLoaderSpi(ClassLoader classLoader) {
        this.classLoader = classLoader == null ? ServiceLoaderSpi.class.getClassLoader() : classLoader;
    }

    @Override
    public String source() {
        return "ServiceLoader";
    }

    @Override
    public List<SpiEntry> load() {
        List<SpiEntry> entries = new ArrayList<>();
        for (Extension extension : ServiceLoader.load(Extension.class, classLoader)) {
            entries.add(new SpiEntry(extension.meta(), Extension.class, extension));
        }
        return List.copyOf(entries);
    }
}
