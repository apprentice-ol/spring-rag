package com.agentframework.infra.modelgateway;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型调用上下文：网关与提供方共享的调用元信息。
 *
 * @param sessionId  会话 id
 * @param nodeId     节点 id
 * @param traceId    链路追踪 id
 * @param timeout    调用超时
 * @param attributes 附加属性
 */
public record ModelCallContext(
        String sessionId,
        String nodeId,
        String traceId,
        Duration timeout,
        Map<String, Object> attributes) {

    public ModelCallContext {
        timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
        attributes = attributes == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    /**
     * @param sessionId 会话 id
     * @param nodeId    节点 id
     * @return 使用默认超时的上下文
     */
    public static ModelCallContext of(String sessionId, String nodeId) {
        return new ModelCallContext(sessionId, nodeId, null, null, null);
    }

    /**
     * @param newTraceId 链路追踪 id
     * @return 绑定追踪 id 后的上下文
     */
    public ModelCallContext withTraceId(String newTraceId) {
        return new ModelCallContext(sessionId, nodeId, newTraceId, timeout, attributes);
    }

    /**
     * @param newTimeout 调用超时
     * @return 覆盖超时后的上下文
     */
    public ModelCallContext withTimeout(Duration newTimeout) {
        return new ModelCallContext(sessionId, nodeId, traceId, newTimeout, attributes);
    }

    /**
     * @param extra 待追加的属性
     * @return 追加属性后的上下文
     */
    public ModelCallContext withAttributes(Map<String, Object> extra) {
        if (extra == null || extra.isEmpty()) {
            return this;
        }
        Map<String, Object> merged = new LinkedHashMap<>(attributes);
        merged.putAll(extra);
        return new ModelCallContext(sessionId, nodeId, traceId, timeout, merged);
    }
}
