package com.agentframework.infra.modelgateway;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 模型发起的工具调用。
 *
 * @param id        调用 id，用于回传工具结果
 * @param name      工具名
 * @param arguments 调用参数
 */
public record ToolCall(String id, String name, Map<String, Object> arguments) {

    public ToolCall {
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        name = name == null ? "" : name;
        arguments = arguments == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }

    /**
     * @param name      工具名
     * @param arguments 调用参数
     * @return 工具调用
     */
    public static ToolCall of(String name, Map<String, Object> arguments) {
        return new ToolCall(null, name, arguments);
    }
}
