package com.agentframework.runtime.persistence;

import com.agentframework.runtime.session.SessionRecord;
import com.agentframework.runtime.slot.SlotSnapshot;
import java.time.Instant;

/**
 * 单步检查点：某个 step 上引擎状态的完整快照。
 *
 * <p>直接复用 {@link SessionRecord}（已含 cursor / messages / output / error）与 {@link SlotSnapshot}，
 * 不额外造一套字段映射；回滚语义是"整份替换"。</p>
 *
 * @param session 会话记录快照
 * @param slots   槽位快照
 * @param edge    走到这一步所经过的边，可为 null（step 0）
 * @param at      记录时间
 */
public record CheckpointEntry(
        SessionRecord session,
        SlotSnapshot slots,
        String edge,
        Instant at) {

    public CheckpointEntry {
        if (session == null) {
            throw new IllegalArgumentException("checkpoint entry requires a session record");
        }
        at = at == null ? Instant.now() : at;
    }

    /** @return 会话 id */
    public String sessionId() {
        return session.sessionId();
    }

    /** @return 步号，取自游标 */
    public int step() {
        return (int) session.cursor().step();
    }
}
