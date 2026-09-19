package com.agentframework.extension.spi;

import com.agentframework.extension.registry.Extension;
import com.agentframework.extension.registry.ExtensionContext;
import com.agentframework.extension.registry.ExtensionRegistry;
import java.util.ArrayList;
import java.util.List;

/**
 * SPI 注册器：把 {@link SpiEntry} 装入 {@link ExtensionRegistry}，并完成扩展初始化。
 */
public final class SpiRegistrar {

    private SpiRegistrar() {
    }

    /**
     * 装载并注册一个来源的全部扩展。
     *
     * @param registry 扩展注册表
     * @param loader   SPI 加载器
     * @return 成功注册的扩展 id 列表
     */
    public static List<String> register(ExtensionRegistry registry, SpiLoader loader) {
        List<String> registered = new ArrayList<>();
        for (SpiEntry entry : loader.load()) {
            register(registry, entry);
            registered.add(entry.meta().id());
        }
        return List.copyOf(registered);
    }

    /**
     * 注册单条记录：已知扩展点则按扩展点注册，否则按 {@link Extension} 注册。
     *
     * @param registry 扩展注册表
     * @param entry    SPI 记录
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void register(ExtensionRegistry registry, SpiEntry entry) {
        Object implementation = entry.implementation();
        Class<?> point = entry.extensionPoint();
        if (implementation instanceof Extension extension) {
            extension.init(ExtensionContext.host(registry));
        }
        if (point != null && point.isInstance(implementation)) {
            registry.register((Class) point, entry.meta().id(), implementation, entry.meta());
        } else if (implementation instanceof Extension extension) {
            registry.registerExtension(extension);
        } else {
            registry.register((Class) implementation.getClass(), entry.meta().id(), implementation, entry.meta());
        }
    }
}
