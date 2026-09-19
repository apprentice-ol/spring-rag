package com.agentframework.extension.sandbox;

import com.agentframework.extension.manifest.Isolation;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 沙箱调用请求。
 *
 * @param pluginId  发起插件 id
 * @param operation 操作名，例如 {@code tool.invoke}
 * @param payload   调用载荷
 * @param timeout   超时限制
 * @param isolation 期望隔离级别
 */
public record SandboxRequest(
        String pluginId,
        String operation,
        Map<String, Object> payload,
        Duration timeout,
        Isolation isolation) {

    public SandboxRequest {
        pluginId = pluginId == null ? "unknown" : pluginId;
        operation = operation == null ? "operation" : operation;
        payload = payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        isolation = isolation == null ? Isolation.NONE : isolation;
    }

    /**
     * @param pluginId  插件 id
     * @param operation 操作名
     * @param payload   载荷
     * @return 使用默认超时与进程内隔离的请求
     */
    public static SandboxRequest of(String pluginId, String operation, Map<String, Object> payload) {
        return new SandboxRequest(pluginId, operation, payload, null, null);
    }
}
