package com.agentframework.runtime.persistence;

import com.agentframework.runtime.workspace.Snapshot;
import java.util.List;
import java.util.Optional;

/** 工作区快照存储接口：用于回放、审计与灾难恢复。 */
public interface WorkspaceSnapshotStore {

    /**
     * 保存快照。
     *
     * @param snapshot 工作区快照
     */
    void save(Snapshot snapshot);

    /**
     * 读取指定快照。
     *
     * @param snapshotId 快照 id
     * @return 工作区快照
     */
    Optional<Snapshot> load(String snapshotId);

    /**
     * 读取某工作区的最新快照。
     *
     * @param workspaceId 工作区 id
     * @return 最新快照
     */
    Optional<Snapshot> latest(String workspaceId);

    /**
     * @param workspaceId 工作区 id
     * @return 该工作区的全部快照，按时间倒序
     */
    List<Snapshot> list(String workspaceId);
}
