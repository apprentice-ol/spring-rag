package com.agentframework.runtime.persistence;

import com.agentframework.runtime.slot.SlotSnapshot;
import java.util.Optional;

/** 槽位存储接口：会话槽位快照的持久化出口。 */
public interface SlotStore {

    /**
     * 保存槽位快照。
     *
     * @param snapshot 槽位快照
     */
    void save(SlotSnapshot snapshot);

    /**
     * 读取会话槽位快照。
     *
     * @param sessionId 会话 id
     * @return 槽位快照
     */
    Optional<SlotSnapshot> load(String sessionId);

    /**
     * 删除会话槽位快照。
     *
     * @param sessionId 会话 id
     * @return 是否确实删除了快照
     */
    boolean delete(String sessionId);
}
