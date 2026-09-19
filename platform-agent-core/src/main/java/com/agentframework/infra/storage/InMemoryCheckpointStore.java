package com.agentframework.infra.storage;

import com.agentframework.runtime.persistence.CheckpointEntry;
import com.agentframework.runtime.persistence.CheckpointStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 内存检查点存储：按会话保存最近 N 步，超出上限时丢弃最旧条目。
 *
 * <p>保留策略存在的意义是让"默认可用"与"内存可控"同时成立；需要完整历史时注入自定义实现。</p>
 */
public final class InMemoryCheckpointStore implements CheckpointStore {

    /** 默认每会话保留的步数。 */
    public static final int DEFAULT_LIMIT = 50;

    private final int maxEntriesPerSession;
    private final Map<String, List<CheckpointEntry>> entries = new LinkedHashMap<>();

    /** 使用默认保留上限构造。 */
    public InMemoryCheckpointStore() {
        this(DEFAULT_LIMIT);
    }

    /**
     * @param maxEntriesPerSession 每会话保留的最大步数，≤0 表示不限制
     */
    public InMemoryCheckpointStore(int maxEntriesPerSession) {
        this.maxEntriesPerSession = maxEntriesPerSession;
    }

    @Override
    public void append(CheckpointEntry entry) {
        if (entry == null || entry.sessionId() == null) {
            return;
        }
        synchronized (entries) {
            List<CheckpointEntry> history = entries.computeIfAbsent(entry.sessionId(), ignored -> new ArrayList<>());
            history.removeIf(existing -> existing.step() >= entry.step());
            history.add(entry);
            history.sort(Comparator.comparingInt(CheckpointEntry::step));
            if (maxEntriesPerSession > 0 && history.size() > maxEntriesPerSession) {
                history.subList(0, history.size() - maxEntriesPerSession).clear();
            }
        }
    }

    @Override
    public List<CheckpointEntry> history(String sessionId) {
        synchronized (entries) {
            return List.copyOf(entries.getOrDefault(sessionId, List.of()));
        }
    }

    @Override
    public Optional<CheckpointEntry> at(String sessionId, int step) {
        return history(sessionId).stream().filter(entry -> entry.step() == step).findFirst();
    }

    @Override
    public int truncateAfter(String sessionId, int step) {
        synchronized (entries) {
            List<CheckpointEntry> history = entries.get(sessionId);
            if (history == null) {
                return 0;
            }
            int before = history.size();
            history.removeIf(entry -> entry.step() > step);
            return before - history.size();
        }
    }

    @Override
    public void delete(String sessionId) {
        synchronized (entries) {
            entries.remove(sessionId);
        }
    }

    /** @return 当前跟踪的会话数量 */
    public int trackedSessions() {
        synchronized (entries) {
            return entries.size();
        }
    }
}
