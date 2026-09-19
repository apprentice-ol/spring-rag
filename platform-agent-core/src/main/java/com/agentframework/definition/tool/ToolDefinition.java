package com.agentframework.definition.tool;

import com.agentframework.definition.policy.CachePolicy;
import com.agentframework.definition.policy.RetryPolicy;
import com.agentframework.definition.policy.TimeoutPolicy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 工具定义：对“能力”的声明式描述。
 *
 * <p>{@code executorRef} 指向加载期绑定的实现，使同一份定义可以在不同宿主间迁移。</p>
 *
 * @param id         工具 id
 * @param version    版本号，缺省为 {@link #LATEST}
 * @param schema     工具契约
 * @param executorRef 实现引用名
 * @param permission 权限声明
 * @param cachePolicy 缓存策略
 * @param timeout    超时策略
 * @param retry      重试策略
 * @param metadata   自定义元数据
 */
public record ToolDefinition(
        String id,
        String version,
        ToolSchema schema,
        String executorRef,
        ToolPermission permission,
        CachePolicy cachePolicy,
        TimeoutPolicy timeout,
        RetryPolicy retry,
        Map<String, Object> metadata) {

    public static final String LATEST = "latest";

    public ToolDefinition {
        Objects.requireNonNull(id, "tool id is required");
        version = version == null || version.isBlank() ? LATEST : version;
        schema = schema == null ? new ToolSchema(id, "", null, null) : schema;
        executorRef = executorRef == null || executorRef.isBlank() ? id : executorRef;
        permission = permission == null ? ToolPermission.none() : permission;
        cachePolicy = cachePolicy == null ? CachePolicy.disabled() : cachePolicy;
        timeout = timeout == null ? TimeoutPolicy.none() : timeout;
        retry = retry == null ? RetryPolicy.none() : retry;
        metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    /**
     * @param id      工具 id
     * @param version 版本号
     * @param schema  工具契约
     * @return 工具定义
     */
    public static ToolDefinition of(String id, String version, ToolSchema schema) {
        return new ToolDefinition(id, version, schema, null, null, null, null, null, null);
    }

    /**
     * @param id     工具 id
     * @param schema 工具契约
     * @return 版本为 latest 的工具定义
     */
    public static ToolDefinition of(String id, ToolSchema schema) {
        return of(id, LATEST, schema);
    }

    /** @return 定义唯一键，形如 {@code calculator@1.0.0} */
    public String key() {
        return id + "@" + version;
    }

    /**
     * @param permission 权限声明
     * @return 覆盖权限后的定义
     */
    public ToolDefinition withPermission(ToolPermission permission) {
        return new ToolDefinition(id, version, schema, executorRef, permission, cachePolicy, timeout, retry, metadata);
    }

    /**
     * @param cachePolicy 缓存策略
     * @return 覆盖缓存策略后的定义
     */
    public ToolDefinition withCache(CachePolicy cachePolicy) {
        return new ToolDefinition(id, version, schema, executorRef, permission, cachePolicy, timeout, retry, metadata);
    }

    /**
     * @param timeout 超时策略
     * @return 覆盖超时策略后的定义
     */
    public ToolDefinition withTimeout(TimeoutPolicy timeout) {
        return new ToolDefinition(id, version, schema, executorRef, permission, cachePolicy, timeout, retry, metadata);
    }

    /**
     * @param retry 重试策略
     * @return 覆盖重试策略后的定义
     */
    public ToolDefinition withRetry(RetryPolicy retry) {
        return new ToolDefinition(id, version, schema, executorRef, permission, cachePolicy, timeout, retry, metadata);
    }

    /**
     * @param executorRef 实现引用名
     * @return 覆盖实现引用后的定义
     */
    public ToolDefinition withExecutorRef(String executorRef) {
        return new ToolDefinition(id, version, schema, executorRef, permission, cachePolicy, timeout, retry, metadata);
    }
}
