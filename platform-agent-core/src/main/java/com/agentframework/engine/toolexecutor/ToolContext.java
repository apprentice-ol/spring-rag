package com.agentframework.engine.toolexecutor;

import com.agentframework.runtime.slot.Slots;
import com.agentframework.runtime.workspace.Workspace;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具执行上下文：工具可以访问的运行态资源。
 *
 * @param sessionId  会话 id
 * @param nodeId     节点 id
 * @param traceId    链路追踪 id
 * @param workspace  工作区，可为 null
 * @param slots      槽位容器，可为 null
 * @param attributes 附加属性
 */
public record ToolContext(
        String sessionId,
        String nodeId,
        String traceId,
        Workspace workspace,
        Slots slots,
        Map<String, Object> attributes) {

    public ToolContext {
        attributes = attributes == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    /**
     * @param sessionId 会话 id
     * @param nodeId    节点 id
     * @return 最小上下文
     */
    public static ToolContext of(String sessionId, String nodeId) {
        return new ToolContext(sessionId, nodeId, null, null, null, null);
    }

    /**
     * @param newTraceId 链路追踪 id
     * @return 绑定追踪 id 后的上下文
     */
    public ToolContext withTraceId(String newTraceId) {
        return new ToolContext(sessionId, nodeId, newTraceId, workspace, slots, attributes);
    }

    /**
     * @param newWorkspace 工作区
     * @return 绑定工作区后的上下文
     */
    public ToolContext withWorkspace(Workspace newWorkspace) {
        return new ToolContext(sessionId, nodeId, traceId, newWorkspace, slots, attributes);
    }

    /**
     * @param newSlots 槽位容器
     * @return 绑定槽位后的上下文
     */
    public ToolContext withSlots(Slots newSlots) {
        return new ToolContext(sessionId, nodeId, traceId, workspace, newSlots, attributes);
    }

    /**
     * @param extra 待追加的属性
     * @return 追加属性后的上下文
     */
    public ToolContext withAttributes(Map<String, Object> extra) {
        if (extra == null || extra.isEmpty()) {
            return this;
        }
        Map<String, Object> merged = new LinkedHashMap<>(attributes);
        merged.putAll(extra);
        return new ToolContext(sessionId, nodeId, traceId, workspace, slots, merged);
    }

    /**
     * @param attributeName 属性名
     * @param defaultValue  缺省值
     * @return 属性值
     */
    public String attribute(String attributeName, String defaultValue) {
        Object value = attributes.get(attributeName);
        return value == null ? defaultValue : String.valueOf(value);
    }
}
