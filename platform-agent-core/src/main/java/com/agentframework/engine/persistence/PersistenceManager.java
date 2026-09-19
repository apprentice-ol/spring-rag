package com.agentframework.engine.persistence;

import com.agentframework.crosscutting.trace.Tracer;
import com.agentframework.runtime.persistence.SessionStore;
import com.agentframework.runtime.persistence.CheckpointEntry;
import com.agentframework.runtime.persistence.CheckpointStore;
import com.agentframework.runtime.persistence.SlotStore;
import com.agentframework.runtime.persistence.WorkspaceSnapshotStore;
import com.agentframework.runtime.session.Cursor;
import com.agentframework.runtime.session.MutableSession;
import com.agentframework.runtime.session.Session;
import com.agentframework.runtime.session.SessionRecord;
import com.agentframework.runtime.slot.SlotSnapshot;
import com.agentframework.runtime.slot.Slots;
import java.time.Instant;
import java.util.List;
import com.agentframework.runtime.workspace.Snapshot;
import com.agentframework.runtime.workspace.Workspace;
import java.util.Optional;

/**
 * 持久化管理器：把会话、游标、槽位与工作区快照统一落库。
 *
 * <p>它同时充当工作流运行时的检查点回调：每推进一步就写入一次，使断点续跑成为默认能力。</p>
 */
public final class PersistenceManager {

    private final SessionStore sessionStore;
    private final SlotStore slotStore;
    private final WorkspaceSnapshotStore snapshotStore;
    private final Tracer tracer;
    private final CheckpointStore checkpoints;

    /**
     * @param sessionStore  会话存储
     * @param slotStore     槽位存储
     * @param snapshotStore 工作区快照存储，可为 null
     * @param tracer        追踪器，可为 null
     */
    public PersistenceManager(SessionStore sessionStore, SlotStore slotStore,
            WorkspaceSnapshotStore snapshotStore, Tracer tracer) {
        this(sessionStore, slotStore, snapshotStore, tracer, null);
    }

    /**
     * @param sessionStore  会话存储
     * @param slotStore     槽位存储
     * @param snapshotStore 工作区快照存储，可为 null
     * @param tracer        追踪器，可为 null
     * @param checkpoints   检查点历史存储，可为 null 表示不保留历史
     */
    public PersistenceManager(SessionStore sessionStore, SlotStore slotStore,
            WorkspaceSnapshotStore snapshotStore, Tracer tracer, CheckpointStore checkpoints) {
        this.sessionStore = sessionStore;
        this.slotStore = slotStore;
        this.snapshotStore = snapshotStore;
        this.tracer = tracer;
        this.checkpoints = checkpoints;
    }

    /**
     * 保存检查点。
     *
     * @param session 会话
     * @param cursor  当前游标
     * @param slots   槽位容器
     */
    public void checkpoint(Session session, Cursor cursor, Slots slots) {
        if (session == null) {
            return;
        }
        if (session instanceof MutableSession mutable && cursor != null) {
            mutable.cursor(cursor);
        }
        sessionStore.save(session.toRecord());
        if (slots != null) {
            slotStore.save(slots.snapshot(session.id()));
        }
        if (checkpoints != null && slots != null) {
            checkpoints.append(new CheckpointEntry(session.toRecord(), slots.snapshot(session.id()),
                    cursor == null ? null : cursor.lastEdge(), Instant.now()));
        }
    }

    /**
     * @param sessionId 会话 id
     * @return 按步号升序的检查点历史
     */
    public List<CheckpointEntry> history(String sessionId) {
        return checkpoints == null ? List.of() : checkpoints.history(sessionId);
    }

    /**
     * @param sessionId 会话 id
     * @param step      步号
     * @return 该步的检查点
     */
    public Optional<CheckpointEntry> checkpointAt(String sessionId, int step) {
        return checkpoints == null ? Optional.empty() : checkpoints.at(sessionId, step);
    }

    /**
     * @param sessionId 会话 id
     * @param step      保留到的步号
     * @return 被截断的条目数
     */
    public int truncateHistory(String sessionId, int step) {
        return checkpoints == null ? 0 : checkpoints.truncateAfter(sessionId, step);
    }

    /**
     * 持久化会话与槽位。
     *
     * @param session 会话
     * @param slots   槽位容器，可为 null
     */
    public void persist(Session session, Slots slots) {
        checkpoint(session, session == null ? null : session.cursor(), slots);
    }

    /**
     * 由存储还原会话。
     *
     * @param sessionId 会话 id
     * @return 会话记录
     */
    public Optional<SessionRecord> restore(String sessionId) {
        return sessionStore.load(sessionId);
    }

    /**
     * 由存储还原槽位。
     *
     * @param sessionId 会话 id
     * @return 槽位快照
     */
    public Optional<SlotSnapshot> restoreSlots(String sessionId) {
        return slotStore.load(sessionId);
    }

    /**
     * 保存工作区快照。
     *
     * @param workspace 工作区
     * @param name      快照名
     * @return 快照对象，未配置存储时返回 null
     */
    public Snapshot snapshotWorkspace(Workspace workspace, String name) {
        if (workspace == null || snapshotStore == null) {
            return null;
        }
        Snapshot snapshot = workspace.snapshot(name);
        snapshotStore.save(snapshot);
        return snapshot;
    }

    /**
     * 导出并清空追踪缓冲。
     */
    public void flushTrace() {
        if (tracer != null) {
            tracer.flush();
        }
    }

    /** @return 已持久化的会话数量 */
    public int storedSessions() {
        return sessionStore.list().size();
    }

    /**
     * 删除会话及其槽位。
     *
     * @param sessionId 会话 id
     * @return 是否确实删除
     */
    public boolean delete(String sessionId) {
        slotStore.delete(sessionId);
        if (checkpoints != null) {
            checkpoints.delete(sessionId);
        }
        return sessionStore.delete(sessionId);
    }
}
