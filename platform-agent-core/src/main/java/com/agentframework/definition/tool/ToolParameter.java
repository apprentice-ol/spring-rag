package com.agentframework.definition.tool;

/**
 * 工具参数声明。
 *
 * @param name         参数名
 * @param type         参数类型（string / number / boolean / object / array）
 * @param required     是否必填
 * @param description  参数说明，供模型理解
 * @param defaultValue 缺省值
 */
public record ToolParameter(
        String name,
        String type,
        boolean required,
        String description,
        Object defaultValue) {

    public ToolParameter {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool parameter name is required");
        }
        type = type == null || type.isBlank() ? "string" : type;
    }

    /**
     * @param name 参数名
     * @param type 参数类型
     * @return 可选参数声明
     */
    public static ToolParameter of(String name, String type) {
        return new ToolParameter(name, type, false, null, null);
    }

    /**
     * @param name 参数名
     * @param type 参数类型
     * @return 必填参数声明
     */
    public static ToolParameter required(String name, String type) {
        return new ToolParameter(name, type, true, null, null);
    }

    /**
     * @param name         参数名
     * @param type         参数类型
     * @param defaultValue 缺省值
     * @return 带缺省值的参数声明
     */
    public static ToolParameter of(String name, String type, Object defaultValue) {
        return new ToolParameter(name, type, false, null, defaultValue);
    }

    /**
     * @param description 参数说明
     * @return 补充说明后的参数声明
     */
    public ToolParameter describedAs(String description) {
        return new ToolParameter(name, type, required, description, defaultValue);
    }
}
