package com.agentframework.extension.permission;

import java.util.Collection;

/**
 * 权限校验器：把“申请的权限”与“宿主授予的权限”做比对。
 *
 * <p>默认策略为最小授权：只有显式授予的权限才可用。</p>
 */
public final class PermissionChecker {

    private final PermissionSet granted;

    /** @param granted 宿主授予的权限集合 */
    public PermissionChecker(PermissionSet granted) {
        this.granted = granted == null ? PermissionSet.none() : granted;
    }

    /** @return 宿主授予的权限 */
    public PermissionSet granted() {
        return granted;
    }

    /**
     * 校验单项权限。
     *
     * @param required 需要的权限
     * @param subject  申请方标识
     * @throws PermissionDeniedException 权限不足时抛出
     */
    public void require(String required, String subject) {
        if (!granted.allows(required)) {
            throw new PermissionDeniedException(subject, required);
        }
    }

    /**
     * 校验多项权限。
     *
     * @param required 需要的权限
     * @param subject  申请方标识
     * @throws PermissionDeniedException 任一项缺失时抛出
     */
    public void requireAll(Collection<String> required, String subject) {
        if (required == null) {
            return;
        }
        for (String permission : required) {
            require(permission, subject);
        }
    }

    /**
     * 计算插件申请的权限中实际被授予的部分。
     *
     * @param requested 插件申请的权限
     * @return 实际生效的权限
     */
    public PermissionSet effective(PermissionSet requested) {
        return requested == null ? PermissionSet.none() : requested.intersect(granted);
    }

    /**
     * 判断是否有权限不足项。
     *
     * @param required 需要的权限
     * @return 是否全部满足
     */
    public boolean allows(Collection<String> required) {
        return granted.allowsAll(required);
    }
}
