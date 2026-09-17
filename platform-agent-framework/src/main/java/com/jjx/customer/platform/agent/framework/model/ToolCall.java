package com.jjx.customer.platform.agent.framework.model;

import java.util.Map;

/**
 * 模型请求的一次工具调用。
 */
public record ToolCall(String toolName, Map<String, Object> args) {

    public ToolCall {
        args = args == null ? Map.of() : Map.copyOf(args);
    }
}
