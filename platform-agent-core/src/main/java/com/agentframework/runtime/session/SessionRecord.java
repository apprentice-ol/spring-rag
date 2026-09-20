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
 * <h2>消息归属</h2>
 *
 * <p>{@link #messages} 是<b>执行内</b>的：引擎在运行过程中把 user / assistant / tool 消息追加进来，
 * 供 {@code {{messages}}} 模板与单次运行内的上下文使用，随会话记录一起持久化以便恢复。</p>
 *
 * <p>它<b>不是对话记录</b>。产品意义上的"用户与系统说过什么"由调用方自持——本平台是
 * {@code sa_message}（含引用溯源、澄清卡片载荷等业务字段，这些是引擎不认识的）。
 * 两者的权威边界：</p>
 *
 * <ul>
 *   <li><b>对话内容以调用方为准</b>（{@code sa_message}）：它更全、带业务语义，且是 UI 的数据源；</li>
 *   <li>{@code messages} 只在单次运行内自洽，用于模板渲染；<b>不要</b>拿它当对话历史回放给用户。</li>
 * </ul>
 *
 * <p>实测本平台所有 prompt 资产均未使用 {@code {{messages}}}，该列表在当前装配下是执行内数据，
 * 不与 {@code sa_message} 竞争。</p>
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
 * @param messages          <b>执行内</b>消息列表（供 {@code {{messages}}} 模板与单次运行内的上下文），
 *                          <b>不是对话记录</b>。对话记录由调用方自持（本平台为 {@code sa_message}），
 *                          两者互不取代：见类注释的「消息归属」一节。
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
