package com.agentframework.runtime.session;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话的扁平可序列化投影。
 *
 * <p>存储层只与 record 打交道，因此数据库、文件、测试替身都能直接接入，无需改动引擎。</p>
 *
 * @param sessionId         会话 id
 * @param tenantId          租户 id
 * @param userId            用户 id
 * @param agentId           Agent id
 * @param agentVersion      Agent 版本
 * @param workflowId        工作流 id
 * @param workflowVersion   工作流版本
 * @param state             会话状态
 * @param cursor            运行游标
 * @param messages          消息列表
 * @param traceId           链路追踪 id
 * @param extensionVersions 固定的扩展版本
 * @param createdAt         创建时间
 * @param updatedAt         更新时间
 * @param output            最终输出
 * @param error             失败原因
 */
public record SessionRecord(
        String sessionId,
        String tenantId,
        String userId,
        String agentId,
        String agentVersion,
        String workflowId,
        String workflowVersion,
        SessionState state,
        Cursor cursor,
        List<Message> messages,
        String traceId,
        Map<String, String> extensionVersions,
        Instant createdAt,
        Instant updatedAt,
        String output,
        String error) {

    public SessionRecord {
        state = state == null ? SessionState.CREATED : state;
        cursor = cursor == null ? Cursor.initial() : cursor;
        messages = List.copyOf(messages == null ? List.of() : messages);
        extensionVersions = extensionVersions == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(extensionVersions));
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    /**
     * @param state     新的会话状态
     * @param updatedAt 更新时间
     * @return 覆盖状态后的记录
     */
    public SessionRecord withState(SessionState state, Instant updatedAt) {
        return new SessionRecord(sessionId, tenantId, userId, agentId, agentVersion, workflowId, workflowVersion,
                state, cursor, messages, traceId, extensionVersions, createdAt, updatedAt, output, error);
    }
}
