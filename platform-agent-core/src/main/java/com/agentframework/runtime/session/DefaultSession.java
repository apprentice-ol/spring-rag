package com.agentframework.runtime.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存态会话实现。
 *
 * <p>本类只维护运行期状态，持久化统一走 {@code SessionStore}，避免运行态与存储耦合。</p>
 */
public final class DefaultSession implements MutableSession {

    private final String id;
    private final String tenantId;
    private final String userId;
    private final String agentId;
    private final String agentVersion;
    private final String workflowId;
    private final String workflowVersion;
    private final Instant createdAt;
    private final List<Message> messages = new CopyOnWriteArrayList<>();
    private final Map<String, String> extensionVersions = new LinkedHashMap<>();

    private volatile SessionState state = SessionState.CREATED;
    private volatile Cursor cursor = Cursor.initial();
    private volatile String traceId;
    private volatile Instant updatedAt;
    private volatile String output;
    private volatile String error;

    /**
     * 创建新会话。
     *
     * @param id              会话 id
     * @param tenantId        租户 id
     * @param userId          用户 id
     * @param agentId         Agent id
     * @param agentVersion    Agent 版本
     * @param workflowId      工作流 id
     * @param workflowVersion 工作流版本
     */
    public DefaultSession(
            String id,
            String tenantId,
            String userId,
            String agentId,
            String agentVersion,
            String workflowId,
            String workflowVersion) {
        this.id = Objects.requireNonNull(id, "session id is required");
        this.tenantId = tenantId;
        this.userId = userId;
        this.agentId = Objects.requireNonNull(agentId, "agent id is required");
        this.agentVersion = agentVersion;
        this.workflowId = Objects.requireNonNull(workflowId, "workflow id is required");
        this.workflowVersion = workflowVersion;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    /**
     * 由持久化记录还原会话。
     *
     * @param record 会话记录
     * @return 会话实例
     */
    public static DefaultSession fromRecord(SessionRecord record) {
        DefaultSession session = new DefaultSession(record.sessionId(), record.tenantId(), record.userId(),
                record.agentId(), record.agentVersion(), record.workflowId(), record.workflowVersion());
        session.state = record.state();
        session.cursor = record.cursor();
        session.messages.clear();
        session.messages.addAll(record.messages());
        session.traceId = record.traceId();
        session.extensionVersions.putAll(record.extensionVersions());
        session.updatedAt = record.updatedAt();
        session.output = record.output();
        session.error = record.error();
        return session;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String tenantId() {
        return tenantId;
    }

    @Override
    public String userId() {
        return userId;
    }

    @Override
    public String agentId() {
        return agentId;
    }

    @Override
    public String agentVersion() {
        return agentVersion;
    }

    @Override
    public String workflowId() {
        return workflowId;
    }

    @Override
    public String workflowVersion() {
        return workflowVersion;
    }

    @Override
    public SessionState state() {
        return state;
    }

    @Override
    public Cursor cursor() {
        return cursor;
    }

    @Override
    public List<Message> messages() {
        return List.copyOf(messages);
    }

    @Override
    public String traceId() {
        return traceId;
    }

    @Override
    public Map<String, String> extensionVersions() {
        synchronized (extensionVersions) {
            return Map.copyOf(extensionVersions);
        }
    }

    @Override
    public Instant createdAt() {
        return createdAt;
    }

    @Override
    public Instant updatedAt() {
        return updatedAt;
    }

    @Override
    public String output() {
        return output;
    }

    @Override
    public String error() {
        return error;
    }

    @Override
    public void state(SessionState state) {
        this.state = Objects.requireNonNull(state, "state is required");
        touch();
    }

    @Override
    public void cursor(Cursor cursor) {
        this.cursor = Objects.requireNonNull(cursor, "cursor is required");
        touch();
    }

    @Override
    public void appendMessage(Message message) {
        messages.add(Objects.requireNonNull(message, "message is required"));
        touch();
    }

    @Override
    public void output(String output) {
        this.output = output;
        touch();
    }

    @Override
    public void error(String error) {
        this.error = error;
        touch();
    }

    @Override
    public void traceId(String traceId) {
        this.traceId = traceId;
        touch();
    }

    @Override
    public void extensionVersion(String extensionId, String version) {
        synchronized (extensionVersions) {
            extensionVersions.put(extensionId, version);
        }
        touch();
    }

    @Override
    public SessionRecord toRecord() {
        return new SessionRecord(id, tenantId, userId, agentId, agentVersion, workflowId, workflowVersion,
                state, cursor, new ArrayList<>(messages), traceId, extensionVersions(), createdAt, updatedAt,
                output, error);
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
