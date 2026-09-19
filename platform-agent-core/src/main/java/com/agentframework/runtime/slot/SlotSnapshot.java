package com.agentframework.runtime.slot;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 槽位快照：槽位容器的可持久化投影，用于会话恢复与回放。
 *
 * @param sessionId 所属会话 id
 * @param values    槽位名到槽位值的映射
 * @param version   容器版本号
 * @param takenAt   快照时间
 */
public record SlotSnapshot(String sessionId, Map<String, SlotValue> values, long version, Instant takenAt) {

    public SlotSnapshot {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("槽位快照必须归属某个会话");
        }
        values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
        takenAt = takenAt == null ? Instant.now() : takenAt;
    }

    /** @return 快照中的槽位数量 */
    public int size() {
        return values.size();
    }
}
