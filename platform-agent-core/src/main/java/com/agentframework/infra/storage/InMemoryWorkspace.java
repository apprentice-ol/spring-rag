package com.agentframework.infra.storage;

import com.agentframework.infra.vector.InMemoryVectorStore;
import com.agentframework.runtime.workspace.AgentFileSystem;
import com.agentframework.runtime.workspace.Artifact;
import com.agentframework.runtime.workspace.Memory;
import com.agentframework.runtime.workspace.Snapshot;
import com.agentframework.runtime.workspace.VectorStore;
import com.agentframework.runtime.workspace.Workspace;
import com.agentframework.runtime.workspace.WorkspacePermissions;
import com.agentframework.runtime.workspace.WorkspaceTemplate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存工作区：文件系统、记忆、产物与向量索引全部驻留在进程内。
 *
 * <p>支持快照与恢复，便于回放与失败重试。</p>
 */
public final class InMemoryWorkspace implements Workspace {

    private final String id;
    private final String sessionId;
    private final InMemoryAgentFileSystem fileSystem = new InMemoryAgentFileSystem();
    private final InMemoryMemory memory = new InMemoryMemory();
    private final InMemoryVectorStore vectorStore = new InMemoryVectorStore();
    private final List<Artifact> artifacts = new ArrayList<>();
    private final List<String> dbConnections = new ArrayList<>();
    private final WorkspacePermissions permissions;
    private long version;

    /**
     * @param id          工作区 id
     * @param sessionId   所属会话 id
     * @param permissions 权限护栏
     */
    public InMemoryWorkspace(String id, String sessionId, WorkspacePermissions permissions) {
        this.id = id;
        this.sessionId = sessionId;
        this.permissions = permissions == null ? WorkspacePermissions.readWrite() : permissions;
    }

    /**
     * 按模板创建并初始化工作区。
     *
     * @param id          工作区 id
     * @param sessionId   所属会话 id
     * @param template    工作区模板
     * @return 初始化完成的工作区
     */
    public static InMemoryWorkspace fromTemplate(String id, String sessionId, WorkspaceTemplate template) {
        WorkspaceTemplate workspaceTemplate = template == null ? WorkspaceTemplate.empty() : template;
        InMemoryWorkspace workspace = new InMemoryWorkspace(id, sessionId, workspaceTemplate.permissions());
        workspaceTemplate.seedFiles().forEach(workspace.fileSystem::writeString);
        workspaceTemplate.seedMemory().forEach(workspace.memory::put);
        return workspace;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String sessionId() {
        return sessionId;
    }

    @Override
    public AgentFileSystem fs() {
        return fileSystem;
    }

    @Override
    public Memory memory() {
        return memory;
    }

    @Override
    public List<Artifact> artifacts() {
        synchronized (artifacts) {
            return List.copyOf(artifacts);
        }
    }

    @Override
    public Artifact addArtifact(Artifact artifact) {
        if (artifact != null) {
            synchronized (artifacts) {
                artifacts.add(artifact);
            }
            version++;
        }
        return artifact;
    }

    @Override
    public VectorStore vectorStore() {
        return vectorStore;
    }

    @Override
    public List<String> dbConnections() {
        synchronized (dbConnections) {
            return List.copyOf(dbConnections);
        }
    }

    /**
     * 登记一个可用的数据库连接名。
     *
     * @param connection 连接名
     */
    public void addDbConnection(String connection) {
        if (connection != null && !connection.isBlank()) {
            synchronized (dbConnections) {
                dbConnections.add(connection);
            }
        }
    }

    @Override
    public WorkspacePermissions permissions() {
        return permissions;
    }

    @Override
    public long version() {
        return version;
    }

    @Override
    public Snapshot snapshot(String name) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("files", fileSystem.files());
        manifest.put("memory", memory.asMap());
        manifest.put("artifacts", artifacts());
        return new Snapshot(name, id, version, null, manifest);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void restore(Snapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        Object files = snapshot.manifest().get("files");
        if (files instanceof Map<?, ?> map) {
            Map<String, String> restored = new LinkedHashMap<>();
            map.forEach((key, value) -> restored.put(String.valueOf(key), String.valueOf(value)));
            fileSystem.restore(restored);
        }
        memory.clear();
        Object memorySnapshot = snapshot.manifest().get("memory");
        if (memorySnapshot instanceof Map<?, ?> map) {
            map.forEach((key, value) -> memory.put(String.valueOf(key), value));
        }
        synchronized (artifacts) {
            artifacts.clear();
            Object artifactSnapshot = snapshot.manifest().get("artifacts");
            if (artifactSnapshot instanceof List<?> list) {
                list.stream().filter(Artifact.class::isInstance).map(Artifact.class::cast).forEach(artifacts::add);
            }
        }
        version = snapshot.version();
    }

    /** @return 只读快照清单，便于断言 */
    public Map<String, Object> stateSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("workspaceId", id);
        summary.put("sessionId", sessionId);
        summary.put("files", fileSystem.size());
        summary.put("memory", memory.size());
        summary.put("artifacts", artifacts().size());
        summary.put("vectors", vectorStore.size());
        summary.put("version", version);
        return Collections.unmodifiableMap(summary);
    }
}
