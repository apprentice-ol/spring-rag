package com.agentframework.runtime.slot;

import com.agentframework.definition.workflow.SlotScope;
import java.time.Instant;

/**
 * 槽位值：运行态中最小粒度的状态单元，带作用域与版本号。
 *
 * @param name      槽位名
 * @param scope     作用域
 * @param scopeKey  作用域实例键（例如 agent 版本键、sessionId），跨会话共享时用于隔离数据
 * @param value     实际值
 * @param version   版本号，每次写入递增，可用于乐观并发校验
 * @param persistent 是否需要持久化
 * @param updatedAt 最近写入时间
 */
public record SlotValue(
        String name,
        SlotScope scope,
        String scopeKey,
        Object value,
        long version,
        boolean persistent,
        Instant updatedAt) {

    public SlotValue {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("槽位名不能为空");
        }
        scope = scope == null ? SlotScope.SESSION : scope;
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }

    /**
     * 构建会话级槽位值。
     *
     * @param name  槽位名
     * @param value 槽位值
     * @return 版本号为 1 的槽位值
     */
    public static SlotValue of(String name, Object value) {
        return new SlotValue(name, SlotScope.SESSION, null, value, 1L, true, null);
    }

    /**
     * 写入新值并递增版本号。
     *
     * @param newValue 新值
     * @return 版本号加一后的槽位值
     */
    public SlotValue withValue(Object newValue) {
        return new SlotValue(name, scope, scopeKey, newValue, version + 1, persistent, Instant.now());
    }
}
