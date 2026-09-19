package com.agentframework.infra.storage;

import com.agentframework.definition.codec.DefinitionKind;
import com.agentframework.runtime.persistence.DefinitionRecord;
import com.agentframework.runtime.persistence.DefinitionStatus;
import com.agentframework.runtime.persistence.DefinitionStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 内存定义存储：用于测试与示例。
 */
public final class InMemoryDefinitionStore implements DefinitionStore {

    private final Map<String, DefinitionRecord> records = new LinkedHashMap<>();

    @Override
    public DefinitionRecord save(DefinitionRecord record) {
        synchronized (records) {
            records.put(key(record), record);
        }
        return record;
    }

    @Override
    public Optional<DefinitionRecord> find(DefinitionKind kind, String id, String version,
            DefinitionStatus status) {
        synchronized (records) {
            return Optional.ofNullable(records.get(key(kind, id, version, status)));
        }
    }

    @Override
    public List<DefinitionRecord> list(DefinitionKind kind, DefinitionStatus status) {
        synchronized (records) {
            return records.values().stream()
                    .filter(record -> record.kind() == kind && record.status() == status)
                    .sorted(Comparator.comparing(DefinitionRecord::createdAt))
                    .toList();
        }
    }

    @Override
    public List<DefinitionRecord> history(DefinitionKind kind, String id) {
        synchronized (records) {
            return records.values().stream()
                    .filter(record -> record.kind() == kind && record.id().equals(id))
                    .sorted(Comparator.comparing(DefinitionRecord::createdAt))
                    .toList();
        }
    }

    @Override
    public boolean delete(DefinitionKind kind, String id, String version, DefinitionStatus status) {
        synchronized (records) {
            return records.remove(key(kind, id, version, status)) != null;
        }
    }

    /** @return 全部记录数量 */
    public int size() {
        synchronized (records) {
            return records.size();
        }
    }

    /**
     * @param record 记录
     * @return 存储键
     */
    private String key(DefinitionRecord record) {
        return key(record.kind(), record.id(), record.version(), record.status());
    }

    /**
     * @param kind    定义种类
     * @param id      定义 id
     * @param version 版本号
     * @param status  状态
     * @return 存储键
     */
    private String key(DefinitionKind kind, String id, String version, DefinitionStatus status) {
        return kind.wireName() + "|" + id + "|" + (version == null ? "latest" : version) + "|" + status;
    }

    /**
     * 供测试构造带发布时间的记录。
     *
     * @param record 记录
     * @return 发布时间为当前的记录
     */
    static DefinitionRecord published(DefinitionRecord record) {
        return record.withGovernance(record.author(), Instant.now());
    }

    /** @return 全部记录的快照 */
    List<DefinitionRecord> snapshot() {
        synchronized (records) {
            return new ArrayList<>(records.values());
        }
    }
}
