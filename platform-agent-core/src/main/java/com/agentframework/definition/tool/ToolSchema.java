package com.agentframework.definition.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具契约：叫什么、接收什么、返回什么。
 *
 * @param name        工具名
 * @param description 工具说明，供模型选择工具时参考
 * @param parameters  参数声明
 * @param returns     返回值类型
 */
public record ToolSchema(String name, String description, List<ToolParameter> parameters, String returns) {

    public ToolSchema {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool schema name is required");
        }
        description = description == null ? "" : description;
        parameters = List.copyOf(parameters == null ? List.of() : parameters);
        returns = returns == null ? "string" : returns;
    }

    /**
     * @param name        工具名
     * @param description 工具说明
     * @param parameters  参数声明
     * @return 工具 schema
     */
    public static ToolSchema of(String name, String description, ToolParameter... parameters) {
        return new ToolSchema(name, description, List.of(parameters), null);
    }

    /**
     * @param name        工具名
     * @param description 工具说明
     * @return 无参工具 schema
     */
    public static ToolSchema noArgs(String name, String description) {
        return new ToolSchema(name, description, List.of(), null);
    }

    /** @return 全部参数名 */
    public Set<String> parameterNames() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        parameters.forEach(parameter -> names.add(parameter.name()));
        return names;
    }

    /** @return 必填参数名 */
    public Set<String> requiredParameters() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        parameters.stream().filter(ToolParameter::required).forEach(parameter -> names.add(parameter.name()));
        return names;
    }

    /**
     * @param name 参数名
     * @return 参数声明，不存在返回 null
     */
    public ToolParameter parameter(String name) {
        return parameters.stream().filter(parameter -> parameter.name().equals(name)).findFirst().orElse(null);
    }

    /**
     * 校验实参：报告必填缺失与未声明参数。
     *
     * @param arguments 实际参数
     * @return 问题列表，为空表示通过
     */
    public List<String> validate(Map<String, Object> arguments) {
        Map<String, Object> supplied = arguments == null ? Map.of() : arguments;
        List<String> problems = new ArrayList<>();
        for (ToolParameter parameter : parameters) {
            Object value = supplied.get(parameter.name());
            if (value == null) {
                if (parameter.required() && parameter.defaultValue() == null) {
                    problems.add("missing required argument '" + parameter.name() + "'");
                }
            }
        }
        for (String key : supplied.keySet()) {
            if (parameter(key) == null) {
                problems.add("unknown argument '" + key + "'");
            }
        }
        return problems;
    }

    /**
     * 注入参数缺省值。
     *
     * @param arguments 实际参数
     * @return 合并缺省值后的参数表
     */
    public Map<String, Object> applyDefaults(Map<String, Object> arguments) {
        Map<String, Object> merged = new LinkedHashMap<>();
        for (ToolParameter parameter : parameters) {
            if (parameter.defaultValue() != null) {
                merged.put(parameter.name(), parameter.defaultValue());
            }
        }
        if (arguments != null) {
            merged.putAll(arguments);
        }
        return merged;
    }
}
