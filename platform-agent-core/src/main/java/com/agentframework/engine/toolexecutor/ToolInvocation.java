package com.agentframework.engine.toolexecutor;

import com.agentframework.crosscutting.guard.ToolAware;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具调用请求：同时也是守卫可识别的工具调用载荷。
 *
 * @param toolId    工具 id
 * @param version   工具版本
 * @param arguments 调用参数
 * @param sessionId 会话 id
 * @param nodeId    节点 id
 */
public record ToolInvocation(
        String toolId,
        String version,
        Map<String, Object> arguments,
        String sessionId,
        String nodeId) implements ToolAware {

    public ToolInvocation {
        if (toolId == null || toolId.isBlank()) {
            throw new IllegalArgumentException("工具调用必须指定 toolId");
        }
        version = version == null || version.isBlank() ? "latest" : version;
        arguments = arguments == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }

    /**
     * @param toolId    工具 id
     * @param arguments 调用参数
     * @return 调用请求
     */
    public static ToolInvocation of(String toolId, Map<String, Object> arguments) {
        return new ToolInvocation(toolId, null, arguments, null, null);
    }

    /**
     * @param sessionId 会话 id
     * @param nodeId    节点 id
     * @return 补充归属信息后的调用请求
     */
    public ToolInvocation withOwner(String sessionId, String nodeId) {
        return new ToolInvocation(toolId, version, arguments, sessionId, nodeId);
    }

    /**
     * @param newVersion 工具版本
     * @return 覆盖版本后的调用请求
     */
    public ToolInvocation withVersion(String newVersion) {
        return new ToolInvocation(toolId, newVersion, arguments, sessionId, nodeId);
    }

    /**
     * @param arguments 新参数
     * @return 替换参数后的调用请求
     */
    public ToolInvocation withArguments(Map<String, Object> arguments) {
        return new ToolInvocation(toolId, version, arguments, sessionId, nodeId);
    }
}
