package com.agentframework.extension.permission;

/** 权限拒绝异常：申请方未获得执行所需权限。 */
public class PermissionDeniedException extends RuntimeException {

    /**
     * @param subject    申请方（插件或工具）标识
     * @param permission 缺失的权限
     */
    public PermissionDeniedException(String subject, String permission) {
        super("主体 " + subject + " 缺少权限：" + permission);
    }
}
