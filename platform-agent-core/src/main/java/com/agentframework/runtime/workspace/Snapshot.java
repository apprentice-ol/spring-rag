package com.agentframework.runtime.workspace;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工作区快照：用于回放与恢复的时间点副本。
 *
 * @param id          快照 id
 * @param workspaceId 所属工作区
 * @param version     工作区版本号
 * @param createdAt   生成时间
 * @param manifest    快照清单（文件、记忆、产物的摘要信息）
 */
public record Snapshot(String id, String workspaceId, long version, Instant createdAt, Map<String, Object> manifest) {

    public Snapshot {
        id = id == null ? "snapshot-" + Instant.now().toEpochMilli() : id;
        createdAt = createdAt == null ? Instant.now() : createdAt;
        manifest = manifest == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(manifest));
    }
}
