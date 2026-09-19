package com.agentframework.infra.storage;

import com.agentframework.runtime.persistence.WorkspaceSnapshotStore;
import com.agentframework.runtime.workspace.Snapshot;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** 内存工作区快照存储：按工作区保留快照序列。 */
public final class InMemoryWorkspaceSnapshotStore implements WorkspaceSnapshotStore {

    private final Map<String, List<Snapshot>> snapshots = new ConcurrentHashMap<>();

    @Override
    public void save(Snapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        snapshots.computeIfAbsent(snapshot.workspaceId(), ignored -> new CopyOnWriteArrayList<>()).add(snapshot);
    }

    @Override
    public Optional<Snapshot> load(String snapshotId) {
        return snapshots.values().stream()
                .flatMap(List::stream)
                .filter(snapshot -> snapshot.id().equals(snapshotId))
                .findFirst();
    }

    @Override
    public Optional<Snapshot> latest(String workspaceId) {
        List<Snapshot> list = snapshots.get(workspaceId);
        if (list == null || list.isEmpty()) {
            return Optional.empty();
        }
        return list.stream().max(Comparator.comparing(Snapshot::createdAt));
    }

    @Override
    public List<Snapshot> list(String workspaceId) {
        List<Snapshot> list = snapshots.get(workspaceId);
        if (list == null) {
            return List.of();
        }
        return list.stream().sorted(Comparator.comparing(Snapshot::createdAt).reversed()).toList();
    }
}
