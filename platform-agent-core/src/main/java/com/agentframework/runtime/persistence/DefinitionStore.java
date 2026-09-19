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
                .max(java.util.Comparator.comparing((DefinitionRecord record) ->
                        record.publishedAt() == null ? record.createdAt() : record.publishedAt())
                        .thenComparing(DefinitionRecord::version, DefinitionStore::compareVersions));
    }

    /**
     * 版本号比较：{@code publishedAt} 落进同一时钟格（Windows 计时器粒度可达 ~15ms，
     * 连续两次 publish 完全可能同值）时以版本号定序，避免 max 稳定序保住旧版本
     * （DeclarativeDefinitionTest.latestResolvesToNewestPublished 在全量跑时的偶发失败根因）。
     *
     * <p>按 {@code .} 分段数字比较（10.0.0 &gt; 9.0.0）；非数字段回退字典序，保证全序。</p>
     *
     * @param left  左版本（null 视为空串）
     * @param right 右版本（null 视为空串）
     * @return 比较结果
     */
    static int compareVersions(String left, String right) {
        String[] a = (left == null ? "" : left).split("\\.");
        String[] b = (right == null ? "" : right).split("\\.");
        int bound = Math.max(a.length, b.length);
        for (int i = 0; i < bound; i++) {
            String l = i < a.length ? a[i] : "";
            String r = i < b.length ? b[i] : "";
            int byNumber;
            try {
                byNumber = Integer.compare(Integer.parseInt(l), Integer.parseInt(r));
            } catch (NumberFormatException e) {
                byNumber = l.compareTo(r);
            }
            if (byNumber != 0) {
                return byNumber;
            }
        }
        return 0;
    }
}
