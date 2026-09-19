package com.agentframework.runtime.persistence;

import com.agentframework.runtime.session.SessionRecord;
import java.util.List;
import java.util.Optional;

/**
 * 会话存储接口：会话及其 cursor 的持久化出口。
 *
 * <p>接口定义在运行态层，实现放在基础设施层，因此内核不依赖任何具体数据库。</p>
 */
public interface SessionStore {

    /**
     * 保存或覆盖会话。
     *
     * @param record 会话记录
     */
    void save(SessionRecord record);

    /**
     * 按 id 读取会话。
     *
     * @param sessionId 会话 id
     * @return 会话记录
     */
    Optional<SessionRecord> load(String sessionId);

    /**
     * @param tenantId 租户 id
     * @return 该租户下的全部会话记录
     */
    List<SessionRecord> listByTenant(String tenantId);

    /** @return 全部会话记录 */
    List<SessionRecord> list();

    /**
     * 删除会话。
     *
     * @param sessionId 会话 id
     * @return 是否确实删除了记录
     */
    boolean delete(String sessionId);
}
