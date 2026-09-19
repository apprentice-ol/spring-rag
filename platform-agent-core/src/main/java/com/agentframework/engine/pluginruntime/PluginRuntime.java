package com.agentframework.engine.pluginruntime;

import com.agentframework.extension.manifest.PluginManifest;
import com.agentframework.extension.manifest.PluginManifestReader;
import com.agentframework.extension.permission.PermissionChecker;
import com.agentframework.extension.permission.PermissionSet;
import com.agentframework.extension.registry.ExtensionRegistry;
import com.agentframework.extension.sandbox.Sandbox;
import com.agentframework.extension.sandbox.Sandboxes;
import com.agentframework.extension.spi.PluginPackageSpi;
import com.agentframework.extension.spi.SpiRegistrar;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件运行时：把插件包加载成受控扩展。
 *
 * <p>约束顺序为：清单校验 → API 版本校验 → 权限收敛 → 沙箱选择 → 扩展注册。
 * 任一环节失败都会被记录在 {@link PluginDescriptor} 中，而不会污染主流程。</p>
 */
public final class PluginRuntime {

    private final ExtensionRegistry registry;
    private final PermissionChecker permissionChecker;
    private final Map<String, Sandbox> sandboxes = new LinkedHashMap<>();
    private final Map<String, PluginDescriptor> plugins = new LinkedHashMap<>();

    /**
     * @param registry          扩展注册表
     * @param permissionChecker 权限校验器，决定宿主任授予哪些权限
     */
    public PluginRuntime(ExtensionRegistry registry, PermissionChecker permissionChecker) {
        this.registry = registry;
        this.permissionChecker = permissionChecker == null
                ? new PermissionChecker(PermissionSet.none())
                : permissionChecker;
        registerSandbox(Sandboxes.echo());
        registerSandbox(Sandboxes.unsupported(com.agentframework.extension.manifest.Isolation.PROCESS));
        registerSandbox(Sandboxes.unsupported(com.agentframework.extension.manifest.Isolation.CONTAINER));
        registerSandbox(Sandboxes.unsupported(com.agentframework.extension.manifest.Isolation.WASM));
    }

    /**
     * 注册沙箱实现。
     *
     * @param sandbox 沙箱
     * @return 当前运行时
     */
    public PluginRuntime registerSandbox(Sandbox sandbox) {
        if (sandbox != null) {
            sandboxes.put(sandbox.level().name(), sandbox);
        }
        return this;
    }

    /**
     * 加载插件包。
     *
     * @param pluginRoot 插件根目录
     * @return 插件描述
     */
    public PluginDescriptor load(Path pluginRoot) {
        PluginManifest manifest;
        try {
            manifest = PluginManifestReader.read(pluginRoot);
        } catch (RuntimeException e) {
            return new PluginDescriptor(PluginManifest.of("unknown", "0.0.0"), pluginRoot, null, null,
                    PluginState.FAILED, e.getMessage());
        }
        try {
            registry.versionCheck(manifest.apiVersion());
        } catch (RuntimeException e) {
            return new PluginDescriptor(manifest, pluginRoot, null, null, PluginState.FAILED, e.getMessage());
        }
        PermissionSet granted = permissionChecker.effective(PermissionSet.of(manifest.permissions()));
        if (!granted.allowsAll(manifest.permissions())) {
            List<String> missing = new ArrayList<>(manifest.permissions());
            missing.removeAll(granted.permissions());
            return new PluginDescriptor(manifest, pluginRoot, granted, null, PluginState.FAILED,
                    "权限未授予：" + missing);
        }
        if (!sandboxes.containsKey(manifest.isolation().name())) {
            return new PluginDescriptor(manifest, pluginRoot, granted, null, PluginState.FAILED,
                    "缺少隔离实现：" + manifest.isolation());
        }
        try {
            List<String> registered = SpiRegistrar.register(registry, new PluginPackageSpi(pluginRoot));
            PluginDescriptor descriptor = new PluginDescriptor(manifest, pluginRoot, granted, registered,
                    PluginState.INITIALIZED, null);
            plugins.put(manifest.key(), descriptor);
            return descriptor;
        } catch (RuntimeException e) {
            return new PluginDescriptor(manifest, pluginRoot, granted, null, PluginState.FAILED, e.getMessage());
        }
    }

    /**
     * @param pluginId 插件 id
     * @return 插件描述，未加载返回 null
     */
    public PluginDescriptor descriptor(String pluginId) {
        return plugins.values().stream()
                .filter(descriptor -> descriptor.manifest().id().equals(pluginId))
                .findFirst()
                .orElse(null);
    }

    /** @return 全部已加载插件 */
    public List<PluginDescriptor> plugins() {
        return List.copyOf(plugins.values());
    }

    /**
     * 取得指定隔离级别的沙箱。
     *
     * @param level 隔离级别名称
     * @return 沙箱，未注册返回 null
     */
    public Sandbox sandbox(String level) {
        return sandboxes.get(level);
    }

    /** 关闭全部插件。 */
    public void close() {
        plugins.replaceAll((key, descriptor) -> descriptor.withState(PluginState.CLOSED));
    }
}
