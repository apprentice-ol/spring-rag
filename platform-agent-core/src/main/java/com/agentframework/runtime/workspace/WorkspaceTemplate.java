package com.agentframework.runtime.workspace;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工作区模板：由 Agent 定义声明的初始内容。
 *
 * @param id          模板 id
 * @param description 模板说明
 * @param seedFiles   初始化写入工作区的文件
 * @param seedMemory  初始化写入的长期记忆
 * @param permissions 初始权限
 */
public record WorkspaceTemplate(
        String id,
        String description,
        Map<String, String> seedFiles,
        Map<String, Object> seedMemory,
        WorkspacePermissions permissions) {

    public WorkspaceTemplate {
        id = id == null || id.isBlank() ? "default" : id;
        description = description == null ? "" : description;
        seedFiles = seedFiles == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(seedFiles));
        seedMemory = seedMemory == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(seedMemory));
        permissions = permissions == null ? WorkspacePermissions.readWrite() : permissions;
    }

    /** @return 空白模板 */
    public static WorkspaceTemplate empty() {
        return new WorkspaceTemplate("default", "", null, null, null);
    }

    /**
     * @param id 模板 id
     * @return 指定 id 的空白模板
     */
    public static WorkspaceTemplate of(String id) {
        return new WorkspaceTemplate(id, "", null, null, null);
    }

    /**
     * @param path    文件路径
     * @param content 文件内容
     * @return 追加初始文件后的模板
     */
    public WorkspaceTemplate withFile(String path, String content) {
        Map<String, String> merged = new LinkedHashMap<>(seedFiles);
        merged.put(path, content);
        return new WorkspaceTemplate(id, description, merged, seedMemory, permissions);
    }

    /**
     * @param key   记忆键
     * @param value 记忆值
     * @return 追加初始记忆后的模板
     */
    public WorkspaceTemplate withMemory(String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(seedMemory);
        merged.put(key, value);
        return new WorkspaceTemplate(id, description, seedFiles, merged, permissions);
    }

    /**
     * @param permissions 权限
     * @return 覆盖权限后的模板
     */
    public WorkspaceTemplate withPermissions(WorkspacePermissions permissions) {
        return new WorkspaceTemplate(id, description, seedFiles, seedMemory, permissions);
    }
}
