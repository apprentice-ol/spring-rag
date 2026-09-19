package com.agentframework.runtime.workspace;

import java.util.List;

/**
 * 工作区权限护栏：工具可以读什么、写什么、能访问哪些网络地址。
 *
 * @param readOnly     是否只读
 * @param allowNetwork 是否允许访问网络
 * @param allowedPaths 允许访问的路径前缀，为空表示不限制
 * @param allowedHosts 允许访问的主机，为空表示不限制
 */
public record WorkspacePermissions(boolean readOnly, boolean allowNetwork, List<String> allowedPaths,
        List<String> allowedHosts) {

    public WorkspacePermissions {
        allowedPaths = List.copyOf(allowedPaths == null ? List.of() : allowedPaths);
        allowedHosts = List.copyOf(allowedHosts == null ? List.of() : allowedHosts);
    }

    /** @return 可读写、无网络的默认权限 */
    public static WorkspacePermissions readWrite() {
        return new WorkspacePermissions(false, false, List.of("/"), List.of());
    }

    /** @return 只读、无网络的权限 */
    public static WorkspacePermissions readOnlyWorkspace() {
        return new WorkspacePermissions(true, false, List.of("/"), List.of());
    }

    /**
     * @param allowedPaths 允许访问的路径前缀
     * @return 限定路径的可读写权限
     */
    public static WorkspacePermissions sandboxed(String... allowedPaths) {
        return new WorkspacePermissions(false, false, List.of(allowedPaths), List.of());
    }

    /**
     * @param path 相对路径
     * @return 是否允许写入
     */
    public boolean allowsWrite(String path) {
        return !readOnly && pathAllowed(path);
    }

    /**
     * @param path 相对路径
     * @return 是否允许读取
     */
    public boolean allowsRead(String path) {
        return pathAllowed(path);
    }

    /** 判断路径是否落在允许前缀内。 */
    private boolean pathAllowed(String path) {
        if (allowedPaths.isEmpty() || path == null) {
            return true;
        }
        return allowedPaths.stream().anyMatch(prefix -> path.startsWith(prefix));
    }
}
