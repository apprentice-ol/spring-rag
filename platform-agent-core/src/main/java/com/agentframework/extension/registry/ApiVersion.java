package com.agentframework.extension.registry;

/**
 * 扩展 API 版本：用于插件与内核的兼容性校验。
 *
 * <p>兼容规则：主版本必须相等，实现方次版本不低于要求版本。</p>
 *
 * @param major 主版本
 * @param minor 次版本
 * @param patch 修订号
 */
public record ApiVersion(int major, int minor, int patch) implements Comparable<ApiVersion> {

    /** 当前框架提供的扩展 API 版本。 */
    public static final ApiVersion CURRENT = new ApiVersion(1, 0, 0);

    /**
     * 解析版本字符串。
     *
     * @param text 形如 {@code 1.2.3} 的版本字符串
     * @return 版本对象
     * @throws IllegalArgumentException 格式非法时抛出
     */
    public static ApiVersion parse(String text) {
        if (text == null || text.isBlank()) {
            return CURRENT;
        }
        String[] parts = text.trim().split("\\.");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            return new ApiVersion(major, minor, patch);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("非法的 API 版本号：" + text, e);
        }
    }

    /**
     * 判断当前版本是否兼容插件要求的版本。
     *
     * @param required 插件声明的版本
     * @return 兼容则返回 true
     */
    public boolean compatibleWith(ApiVersion required) {
        if (required == null) {
            return true;
        }
        return major == required.major() && minor >= required.minor();
    }

    @Override
    public int compareTo(ApiVersion other) {
        if (major != other.major) {
            return Integer.compare(major, other.major);
        }
        if (minor != other.minor) {
            return Integer.compare(minor, other.minor);
        }
        return Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
