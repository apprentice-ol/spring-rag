package com.agentframework.definition.tool;

import java.util.List;

/**
 * 工具权限：工具在沙箱内可以触碰的资源范围。
 *
 * @param scopes           权限范围标签，例如 read / write
 * @param network          是否允许访问网络
 * @param fileWrite        是否允许写文件
 * @param requiresApproval 是否必须人工审批后才能执行
 */
public record ToolPermission(List<String> scopes, boolean network, boolean fileWrite, boolean requiresApproval) {

    public ToolPermission {
        scopes = List.copyOf(scopes == null ? List.of() : scopes);
    }

    /** @return 无任何权限 */
    public static ToolPermission none() {
        return new ToolPermission(List.of(), false, false, false);
    }

    /** @return 只读权限 */
    public static ToolPermission readOnly() {
        return new ToolPermission(List.of("read"), false, false, false);
    }

    /** @return 读写 + 网络权限 */
    public static ToolPermission full() {
        return new ToolPermission(List.of("read", "write"), true, true, false);
    }

    /**
     * @param scopes 权限范围标签
     * @return 指定范围的权限声明
     */
    public static ToolPermission of(String... scopes) {
        return new ToolPermission(List.of(scopes), false, false, false);
    }

    /** @return 追加网络权限后的声明 */
    public ToolPermission withNetwork() {
        return new ToolPermission(scopes, true, fileWrite, requiresApproval);
    }

    /** @return 追加写文件权限后的声明 */
    public ToolPermission withFileWrite() {
        return new ToolPermission(scopes, network, true, requiresApproval);
    }

    /** @return 标记为需要人工审批的声明 */
    public ToolPermission withApproval() {
        return new ToolPermission(scopes, network, fileWrite, true);
    }
}
