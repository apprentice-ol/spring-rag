package com.agentframework.runtime.workspace;

import java.util.List;

/**
 * 工作区：会话的执行载体，承载文件、产物、记忆与检索索引。
 *
 * <p>一个会话恰好拥有一个工作区；多个会话可以通过传入相同的 {@code workspaceId} 共享同一个工作区。</p>
 */
public interface Workspace {

    /** @return 工作区 id */
    String id();

    /** @return 所属会话 id */
    String sessionId();

    /** @return 文件系统视图 */
    AgentFileSystem fs();

    /** @return 长期记忆 */
    Memory memory();

    /** @return 已登记的产物列表 */
    List<Artifact> artifacts();

    /**
     * @param artifact 产物
     * @return 登记后的产物
     */
    Artifact addArtifact(Artifact artifact);

    /** @return 向量索引 */
    VectorStore vectorStore();

    /** @return 可用的数据库连接名列表 */
    List<String> dbConnections();

    /** @return 权限护栏 */
    WorkspacePermissions permissions();

    /** @return 工作区版本号，每次写入递增 */
    long version();

    /**
     * 生成快照。
     *
     * @param name 快照名
     * @return 快照对象
     */
    Snapshot snapshot(String name);

    /** @param snapshot 需要恢复的快照 */
    void restore(Snapshot snapshot);
}
