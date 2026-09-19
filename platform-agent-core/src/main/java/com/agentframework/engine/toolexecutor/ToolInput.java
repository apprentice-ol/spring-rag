package com.agentframework.engine.toolexecutor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具调用参数。
 *
 * @param arguments 参数表
 * @param sessionId 会话 id
 * @param nodeId    节点 id
 */
public record ToolInput(Map<String, Object> arguments, String sessionId, String nodeId) {

    public ToolInput {
        arguments = arguments == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }

    /**
     * @param arguments 参数表
     * @return 不含会话信息的输入
     */
    public static ToolInput of(Map<String, Object> arguments) {
        return new ToolInput(arguments, null, null);
    }

    /**
     * 读取字符串参数。
     *
     * @param name         参数名
     * @param defaultValue 缺省值
     * @return 参数值
     */
    public String string(String name, String defaultValue) {
        Object value = arguments.get(name);
        return value == null ? defaultValue : String.valueOf(value);
    }

    /**
     * 读取整型参数。
     *
     * @param name         参数名
     * @param defaultValue 缺省值
     * @return 参数值
     */
    public int integer(String name, int defaultValue) {
        Object value = arguments.get(name);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return value == null ? defaultValue : Integer.parseInt(String.valueOf(value));
    }

    /**
     * 读取布尔参数。
     *
     * @param name         参数名
     * @param defaultValue 缺省值
     * @return 参数值
     */
    public boolean bool(String name, boolean defaultValue) {
        Object value = arguments.get(name);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value == null ? defaultValue : Boolean.parseBoolean(String.valueOf(value));
    }
}
