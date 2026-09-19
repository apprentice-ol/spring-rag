package com.agentframework.extension.permission;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 权限集合：插件声明申请的权限，宿主授予实际可用的权限。
 *
 * @param permissions 权限标识集合，例如 {@code network}、{@code file.write}、{@code model}、{@code tool.calculator}
 */
public record PermissionSet(Set<String> permissions) {

    /** 通配权限：表示全部允许。 */
    public static final String WILDCARD = "*";

    public PermissionSet {
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    /** @return 空权限集合 */
    public static PermissionSet none() {
        return new PermissionSet(Set.of());
    }

    /** @return 全部权限 */
    public static PermissionSet all() {
        return new PermissionSet(Set.of(WILDCARD));
    }

    /**
     * @param permissions 权限标识
     * @return 权限集合
     */
    public static PermissionSet of(String... permissions) {
        return new PermissionSet(Set.of(permissions));
    }

    /**
     * @param permissions 权限标识集合
     * @return 权限集合
     */
    public static PermissionSet of(Collection<String> permissions) {
        return new PermissionSet(new LinkedHashSet<>(permissions));
    }

    /**
     * 判断是否拥有某项权限。
     *
     * @param permission 权限标识
     * @return 拥有返回 true
     */
    public boolean allows(String permission) {
        return permissions.contains(WILDCARD) || permissions.contains(permission);
    }

    /**
     * 判断是否同时拥有多项权限。
     *
     * @param required 需要的权限
     * @return 全部拥有返回 true
     */
    public boolean allowsAll(Collection<String> required) {
        return required == null || required.stream().allMatch(this::allows);
    }

    /**
     * 求交集，用于“申请权限 ∩ 宿主授予权限”。
     *
     * @param granted 宿主授予的权限
     * @return 实际可用权限
     */
    public PermissionSet intersect(PermissionSet granted) {
        if (granted == null) {
            return none();
        }
        if (granted.permissions.contains(WILDCARD)) {
            return this;
        }
        Set<String> intersection = new LinkedHashSet<>(permissions);
        intersection.retainAll(granted.permissions);
        return new PermissionSet(intersection);
    }

    /**
     * 合并权限。
     *
     * @param other 另一组权限
     * @return 合并结果
     */
    public PermissionSet union(PermissionSet other) {
        Set<String> merged = new LinkedHashSet<>(permissions);
        if (other != null) {
            merged.addAll(other.permissions);
        }
        return new PermissionSet(merged);
    }
}
