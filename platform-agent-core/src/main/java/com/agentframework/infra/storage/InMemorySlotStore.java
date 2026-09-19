package com.agentframework.infra.storage;

import com.agentframework.runtime.persistence.SlotStore;
import com.agentframework.runtime.slot.SlotSnapshot;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 内存槽位存储：按会话保存槽位快照。 */
public final class InMemorySlotStore implements SlotStore {

    private final Map<String, SlotSnapshot> snapshots = new ConcurrentHashMap<>();

    @Override
    public void save(SlotSnapshot snapshot) {
        if (snapshot != null && snapshot.sessionId() != null) {
            snapshots.put(snapshot.sessionId(), snapshot);
        }
    }

    @Override
    public Optional<SlotSnapshot> load(String sessionId) {
        return Optional.ofNullable(snapshots.get(sessionId));
    }

    @Override
    public boolean delete(String sessionId) {
        return snapshots.remove(sessionId) != null;
    }

    /** @return 已保存的快照数量 */
    public int size() {
        return snapshots.size();
    }
}
