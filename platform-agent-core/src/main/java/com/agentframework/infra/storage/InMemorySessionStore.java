package com.agentframework.infra.storage;

import com.agentframework.runtime.persistence.SessionStore;
import com.agentframework.runtime.session.SessionRecord;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存会话存储：保存会话与 cursor，支持断点续跑的进程内验证。
 *
 * <p>进程重启后数据丢失，生产环境请替换为持久化实现。</p>
 */
public final class InMemorySessionStore implements SessionStore {

    private final Map<String, SessionRecord> sessions = new ConcurrentHashMap<>();

    @Override
    public void save(SessionRecord record) {
        if (record != null && record.sessionId() != null) {
            sessions.put(record.sessionId(), record);
        }
    }

    @Override
    public Optional<SessionRecord> load(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public List<SessionRecord> listByTenant(String tenantId) {
        return sessions.values().stream()
                .filter(record -> tenantId == null || tenantId.equals(record.tenantId()))
                .sorted(Comparator.comparing(SessionRecord::createdAt))
                .toList();
    }

    @Override
    public List<SessionRecord> list() {
        return sessions.values().stream().sorted(Comparator.comparing(SessionRecord::createdAt)).toList();
    }

    @Override
    public boolean delete(String sessionId) {
        return sessions.remove(sessionId) != null;
    }

    /** @return 已保存的会话数量 */
    public int size() {
        return sessions.size();
    }
}
