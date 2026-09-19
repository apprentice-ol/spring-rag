package com.agentframework.runtime.persistence;

import java.util.List;
import java.util.Optional;

/**
 * 检查点历史存储：按 step 保存可寻址的引擎状态快照。
 *
 * <p>与 {@link SessionStore} / {@link SlotStore} 的区别：后者只保留最新一份，用于断点续跑；
 * 本接口保留历史，用于回溯与回滚。默认实现是内存版，生产可换数据库。</p>
 */
public interface CheckpointStore {

    /**
     * 追加一份快照。
     *
     * @param entry 检查点条目
     */
    void append(CheckpointEntry entry);

    /**
     * @param sessionId 会话 id
     * @return 按步号升序排列的历史，无记录时为空列表
     */
    List<CheckpointEntry> history(String sessionId);

    /**
     * @param sessionId 会话 id
     * @param step      步号
     * @return 该步的条目
     */
    Optional<CheckpointEntry> at(String sessionId, int step);

    /**
     * 丢弃某一步之后的全部条目。
     *
     * @param sessionId 会话 id
     * @param step      保留到的步号
     * @return 被丢弃的条目数
     */
    int truncateAfter(String sessionId, int step);

    /**
     * 删除会话的全部历史。
     *
     * @param sessionId 会话 id
     */
    void delete(String sessionId);
}
