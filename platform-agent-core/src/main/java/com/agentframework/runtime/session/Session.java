package com.agentframework.runtime.session;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 会话：{@code AgentDefinition} 的运行实例。
 *
 * <p>一次执行所触及的一切都可从这里到达——游标、消息、工作区与槽位（工作区与槽位通过
 * {@code ContextManager} 按 sessionId 关联）。</p>
 */
public interface Session {

    /** @return 会话 id */
    String id();

    /** @return 租户 id */
    String tenantId();

    /** @return 用户 id */
    String userId();

    /** @return 使用的 Agent id */
    String agentId();

    /** @return 使用的 Agent 版本 */
    String agentVersion();

    /** @return 使用的工作流 id */
    String workflowId();

    /** @return 使用的工作流版本 */
    String workflowVersion();

    /** @return 当前状态 */
    SessionState state();

    /** @return 当前游标 */
    Cursor cursor();

    /** @return 消息列表快照 */
    List<Message> messages();

    /** @return 链路追踪 id */
    String traceId();

    /** @return 本次会话固定的扩展版本，保证重放一致 */
    Map<String, String> extensionVersions();

    /** @return 创建时间 */
    Instant createdAt();

    /** @return 最近更新时间 */
    Instant updatedAt();

    /** @return 最终输出 */
    String output();

    /** @return 失败原因 */
    String error();

    /** @return 用于持久化的不可变视图 */
    SessionRecord toRecord();
}
