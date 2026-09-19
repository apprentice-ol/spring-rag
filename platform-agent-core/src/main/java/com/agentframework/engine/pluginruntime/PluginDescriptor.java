package com.agentframework.engine.pluginruntime;

import com.agentframework.extension.manifest.Isolation;
import com.agentframework.extension.manifest.PluginManifest;
import com.agentframework.extension.permission.PermissionSet;
import java.nio.file.Path;
import java.util.List;

/**
 * 插件运行时描述：清单 + 实际授予权限 + 注册结果。
 *
 * @param manifest       插件清单
 * @param root           插件目录
 * @param granted        宿主实际授予的权限（申请权限与宿主权限的交集）
 * @param registeredIds  已注册的扩展 id
 * @param state          生命周期状态
 * @param error          失败原因
 */
public record PluginDescriptor(
        PluginManifest manifest,
        Path root,
        PermissionSet granted,
        List<String> registeredIds,
        PluginState state,
        String error) {

    public PluginDescriptor {
        granted = granted == null ? PermissionSet.none() : granted;
        registeredIds = List.copyOf(registeredIds == null ? List.of() : registeredIds);
        state = state == null ? PluginState.DISCOVERED : state;
    }

    /** @return 插件要求的隔离级别 */
    public Isolation isolation() {
        return manifest.isolation();
    }

    /** @return 是否加载成功 */
    public boolean loaded() {
        return state == PluginState.INITIALIZED || state == PluginState.LOADED;
    }

    /**
     * @param newState 新状态
     * @return 覆盖状态后的描述
     */
    public PluginDescriptor withState(PluginState newState) {
        return new PluginDescriptor(manifest, root, granted, registeredIds, newState, error);
    }

    /**
     * @param reason 失败原因
     * @return 标记为失败后的描述
     */
    public PluginDescriptor failed(String reason) {
        return new PluginDescriptor(manifest, root, granted, registeredIds, PluginState.FAILED, reason);
    }
}
