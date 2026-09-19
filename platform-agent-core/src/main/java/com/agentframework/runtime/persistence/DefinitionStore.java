package com.agentframework.runtime.persistence;

import com.agentframework.definition.codec.DefinitionKind;
import java.util.List;
import java.util.Optional;

/**
 * 定义存储：保存声明式定义的草稿、已发布与归档版本。
 *
 * <p>与 {@code DefinitionSource} 的分工：本接口是**写入侧 + 全量读取**，
 * 引擎侧只通过 {@code DefinitionSource} 读取已发布版本。</p>
 */
public interface DefinitionStore {

    /**
     * 保存记录（同 kind/id/version/status 覆盖）。
     *
     * @param record 记录
     * @return 保存后的记录
     */
    DefinitionRecord save(DefinitionRecord record);

    /**
     * @param kind    定义种类
     * @param id      定义 id
     * @param version 版本号
     * @param status  状态
     * @return 记录
     */
    Optional<DefinitionRecord> find(DefinitionKind kind, String id, String version, DefinitionStatus status);

    /**
     * @param kind   定义种类
     * @param status 状态
     * @return 该状态下的全部记录
     */
    List<DefinitionRecord> list(DefinitionKind kind, DefinitionStatus status);

    /**
     * @param kind 定义种类
     * @param id   定义 id
     * @return 该定义的版本历史（按创建时间升序）
     */
    List<DefinitionRecord> history(DefinitionKind kind, String id);

    /**
     * @param kind    定义种类
     * @param id      定义 id
     * @param version 版本号
     * @param status  状态
     * @return 是否确实删除
     */
    boolean delete(DefinitionKind kind, String id, String version, DefinitionStatus status);

    /**
     * @param kind 定义种类
     * @param id   定义 id
     * @return 最近发布的版本
     */
    default Optional<DefinitionRecord> latestPublished(DefinitionKind kind, String id) {
        return list(kind, DefinitionStatus.PUBLISHED).stream()
                .filter(record -> record.id().equals(id))
                .max(java.util.Comparator.comparing(record ->
                        record.publishedAt() == null ? record.createdAt() : record.publishedAt()));
    }
}
