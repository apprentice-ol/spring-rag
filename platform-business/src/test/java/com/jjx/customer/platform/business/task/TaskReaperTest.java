package com.jjx.customer.platform.business.task;

import com.agentframework.engine.agentmanager.Agent;
import com.agentframework.engine.contextmanager.ContextManager;
import com.agentframework.runtime.session.Session;
import com.agentframework.runtime.session.SessionRecord;
import com.agentframework.runtime.session.StartOptions;
import com.agentframework.runtime.slot.SlotSnapshot;
import com.agentframework.runtime.slot.Slots;
import com.agentframework.runtime.workspace.Workspace;
import com.agentframework.runtime.workspace.WorkspaceTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.customer.platform.business.task.entity.AgentTaskEntity;
import com.jjx.customer.platform.config.properties.AgentProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回收器的编排逻辑：两段各自独立失败、未启用回收时只报数不动手、开启后按 attempt 逐会话回收。
 *
 * <p>时间的判定条件（"多久算过期/超期"）已在真实 PG 上单独验证过；本测试只钉<b>编排</b>——
 * 那是 SQL 覆盖不到、又最容易写错的部分。</p>
 */
class TaskReaperTest {

    /** 可编程的回收存储替身。 */
    private static final class FakeStore implements TaskReaperStore {
        int abandonStaleResult;
        RuntimeException abandonStaleThrows;
        List<AgentTaskEntity> reclaimable = List.of();
        RuntimeException findReclaimableThrows;
        final List<String> marked = new ArrayList<>();

        @Override
        public int abandonStale(int ttlMinutes) {
            if (abandonStaleThrows != null) {
                throw abandonStaleThrows;
            }
            return abandonStaleResult;
        }

        @Override
        public List<AgentTaskEntity> findReclaimable(int retentionHours) {
            if (findReclaimableThrows != null) {
                throw findReclaimableThrows;
            }
            return reclaimable;
        }

        @Override
        public int markEngineReclaimed(String taskId) {
            marked.add(taskId);
            return 1;
        }

        List<String> orphans = List.of();
        RuntimeException findOrphansThrows;

        @Override
        public List<String> findOrphanEngineSessions(int retentionHours, int batchSize) {
            if (findOrphansThrows != null) {
                throw findOrphansThrows;
            }
            return orphans;
        }
    }

    /** 记录被释放的会话 id 的上下文管理器替身。 */
    private static final class RecordingContextManager implements ContextManager {
        final List<String> released = new ArrayList<>();

        @Override public Session createSession(Agent agent, StartOptions options) { return null; }
        @Override public Workspace workspace(String sessionId) { return null; }
        @Override public Slots slots(String sessionId) { return null; }
        @Override public void persist(Session session) { }
        @Override public Optional<Session> load(String sessionId) { return Optional.empty(); }
        @Override public Session restore(SessionRecord record, SlotSnapshot slots) { return null; }
        @Override public void release(String sessionId) { released.add(sessionId); }
        @Override public ContextManager registerTemplate(WorkspaceTemplate template) { return this; }
        @Override public Map<String, WorkspaceTemplate> templates() { return Map.of(); }
        @Override public boolean ephemeral(String sessionId) { return false; }
    }

    /** 只记录 DELETE 的 JdbcTemplate 替身（引擎 store 的 delete 走 update）。 */
    private static final class RecordingJdbc extends JdbcTemplate {
        final List<String> deletes = new ArrayList<>();

        @Override
        public void execute(String sql) {
            // 构造期建表语句：测试不依赖数据源
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            return List.of();
        }

        @Override
        public int update(String sql, Object... args) {
            if (sql.startsWith("DELETE")) {
                deletes.add(String.valueOf(args[0]));
            }
            return 1;
        }
    }

    private static AgentTaskEntity task(String taskId, Integer attemptCount) {
        AgentTaskEntity e = new AgentTaskEntity();
        e.setTaskId(taskId);
        e.setAttemptCount(attemptCount);
        return e;
    }

    private static AgentProperties props(int retentionHours) {
        AgentProperties p = new AgentProperties();
        p.setSessionTtlMinutes(60);
        p.setEngineRetentionHours(retentionHours);
        return p;
    }

    private static TaskReaper reaperOf(FakeStore store, int retentionHours,
                                       RecordingJdbc jdbc, RecordingContextManager ctx) {
        return new TaskReaper(store, props(retentionHours), new ObjectMapper(), jdbc, ctx);
    }

    // ---- 默认路径：回收未启用 ----

    @Test
    void 回收未启用时只报数_不删不标记() {
        FakeStore store = new FakeStore();
        store.reclaimable = List.of(task("t1", 2));
        RecordingJdbc jdbc = new RecordingJdbc();
        RecordingContextManager ctx = new RecordingContextManager();

        reaperOf(store, 0, jdbc, ctx).sweep();

        assertTrue(jdbc.deletes.isEmpty(), "默认（retentionHours=0）绝不能删任何东西");
        assertTrue(ctx.released.isEmpty(), "也不该释放内存——什么都没回收");
        assertTrue(store.marked.isEmpty(), "未回收就不能标已回收，否则永远轮不到它");
    }

    @Test
    void 无可回收任务时不动作() {
        FakeStore store = new FakeStore();
        RecordingJdbc jdbc = new RecordingJdbc();

        reaperOf(store, 72, jdbc, new RecordingContextManager()).sweep();

        assertTrue(jdbc.deletes.isEmpty());
        assertTrue(store.marked.isEmpty());
    }

    // ---- 开启回收 ----

    @Test
    void 开启回收_按attempt数逐会话删除并释放内存() {
        FakeStore store = new FakeStore();
        store.reclaimable = List.of(task("task-a", 3));
        RecordingJdbc jdbc = new RecordingJdbc();
        RecordingContextManager ctx = new RecordingContextManager();

        reaperOf(store, 72, jdbc, ctx).sweep();

        // 每个 attempt 一个引擎会话，会话与槽位各删一次 → 3 × 2 = 6 条 DELETE
        assertEquals(6, jdbc.deletes.size(), "实际=" + jdbc.deletes);
        assertTrue(jdbc.deletes.contains("ops-task-a#1"), "实际=" + jdbc.deletes);
        assertTrue(jdbc.deletes.contains("ops-task-a#3"), "实际=" + jdbc.deletes);
        assertEquals(List.of("ops-task-a#1", "ops-task-a#2", "ops-task-a#3"), ctx.released,
                "只删库不摘内存缓存 = 回收只做了一半");
        assertEquals(List.of("task-a"), store.marked);
    }

    @Test
    void attempt数为零或缺失时不推导出任何会话() {
        FakeStore store = new FakeStore();
        store.reclaimable = List.of(task("task-b", 0), task("task-c", null));
        RecordingJdbc jdbc = new RecordingJdbc();

        reaperOf(store, 72, jdbc, new RecordingContextManager()).sweep();

        assertTrue(jdbc.deletes.isEmpty(), "没跑过 attempt 就没会话可回收，实际=" + jdbc.deletes);
        assertEquals(2, store.marked.size(), "但任务本身仍应被标记，避免每轮重复扫描");
    }

    // ---- 两段各自独立失败 ----

    @Test
    void 清扫失败不应阻断回收() {
        FakeStore store = new FakeStore();
        store.abandonStaleThrows = new IllegalStateException("模拟 DB 抖动");
        store.reclaimable = List.of(task("task-d", 1));
        RecordingJdbc jdbc = new RecordingJdbc();

        reaperOf(store, 72, jdbc, new RecordingContextManager()).sweep();

        assertEquals(2, jdbc.deletes.size(), "清扫失败不该让回收也停摆，实际=" + jdbc.deletes);
        assertEquals(List.of("task-d"), store.marked);
    }

    // ---- 孤儿引擎会话（不属于任何任务的历史遗留）----

    @Test
    void 孤儿会话在回收未启用时不删() {
        FakeStore store = new FakeStore();
        store.orphans = List.of("orphan-uuid-1", "orphan-uuid-2");
        RecordingJdbc jdbc = new RecordingJdbc();
        RecordingContextManager ctx = new RecordingContextManager();

        reaperOf(store, 0, jdbc, ctx).sweep();

        assertTrue(jdbc.deletes.isEmpty(), "默认路径下孤儿也不能删，实际=" + jdbc.deletes);
        assertTrue(ctx.released.isEmpty());
    }

    @Test
    void 启用回收时孤儿会话被删除并摘缓存() {
        FakeStore store = new FakeStore();
        store.orphans = List.of("orphan-uuid-1");
        RecordingJdbc jdbc = new RecordingJdbc();
        RecordingContextManager ctx = new RecordingContextManager();

        reaperOf(store, 72, jdbc, ctx).sweep();

        // 孤儿没有 attempt 概念，直接按 sessionId 删会话 + 槽位
        assertEquals(2, jdbc.deletes.size(), "实际=" + jdbc.deletes);
        assertTrue(jdbc.deletes.contains("orphan-uuid-1"), "实际=" + jdbc.deletes);
        assertEquals(List.of("orphan-uuid-1"), ctx.released, "只删库不摘缓存 = 回收只做了一半");
    }

    @Test
    void 孤儿查询失败不影响任务回收() {
        FakeStore store = new FakeStore();
        store.findOrphansThrows = new IllegalStateException("模拟 DB 抖动");
        store.reclaimable = List.of(task("task-e", 1));
        RecordingJdbc jdbc = new RecordingJdbc();

        reaperOf(store, 72, jdbc, new RecordingContextManager()).sweep();

        assertEquals(2, jdbc.deletes.size(), "孤儿扫不动不该拖垮任务回收，实际=" + jdbc.deletes);
        assertEquals(List.of("task-e"), store.marked);
    }

    @Test
    void 可回收查询失败时不上抛() {
        FakeStore store = new FakeStore();
        store.findReclaimableThrows = new IllegalStateException("模拟 DB 抖动");
        RecordingJdbc jdbc = new RecordingJdbc();

        reaperOf(store, 72, jdbc, new RecordingContextManager()).sweep();

        assertTrue(jdbc.deletes.isEmpty(), "查不到候选就什么都不做——绝不猜着删");
    }
}
