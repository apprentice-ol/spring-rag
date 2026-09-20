package com.jjx.customer.platform.business.engine.persistence;

import com.agentframework.runtime.session.SessionRecord;
import com.agentframework.runtime.session.SessionState;
import com.agentframework.runtime.slot.SlotSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 引擎存储的失败语义：「读不到」必须与「没有」可区分。
 *
 * <p>背景：两个 store 原先 read/write 失败一律 {@code log.warn} 后返回 empty / 静默返回。
 * 后果不是"降级"，而是<b>伪结论</b>——槽位快照读失败时调用方拿**空槽位**继续 resume，
 * {@code pending_ask} 一并丢失使 {@code ActExecutor} 的幂等重入分支被跳过，
 * 最终吐出「诊断流程结束但未产出结论」这种看起来像正常收尾、实则是坏掉的输出。</p>
 *
 * <p>本测试同时守住两个方向：失败必须上抛，<b>且"无数据"仍须正常返回空</b>——
 * 防止修复时把 {@code Optional.empty()} 这个合法语义一并改坏。</p>
 */
class EngineStoreFailureTest {

    /** 只覆写被测方法：DDL 跳过（构造期会执行），query/update 按开关抛异常。 */
    private static class StubJdbc extends JdbcTemplate {

        private final boolean failOnQuery;
        private final boolean failOnUpdate;
        private final List<Object> rows;

        StubJdbc(boolean failOnQuery, boolean failOnUpdate, List<Object> rows) {
            this.failOnQuery = failOnQuery;
            this.failOnUpdate = failOnUpdate;
            this.rows = rows;
        }

        @Override
        public void execute(String sql) {
            // 构造期建表语句：测试不依赖真实数据源
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            if (failOnQuery) {
                throw new DataAccessResourceFailureException("模拟 DB 不可用");
            }
            return (List<T>) rows;
        }

        @Override
        public int update(String sql, Object... args) {
            if (failOnUpdate) {
                throw new DataAccessResourceFailureException("模拟 DB 不可用");
            }
            return 1;
        }
    }

    private static PgEngineSlotStore slotStore(StubJdbc jdbc) {
        return new PgEngineSlotStore(jdbc, new ObjectMapper());
    }

    private static PgEngineSessionStore sessionStore(StubJdbc jdbc) {
        return new PgEngineSessionStore(jdbc, new ObjectMapper());
    }

    // ---- 槽位存储 ----

    @Test
    void 槽位读取失败必须上抛而不是当成没有() {
        PgEngineSlotStore store = slotStore(new StubJdbc(true, false, List.of()));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> store.load("ops-x#1"));

        assertTrue(e.getMessage().contains("读取失败"), "异常应说明是读取失败而非无数据：" + e.getMessage());
    }

    @Test
    void 槽位确实无数据时仍返回空() {
        // 防过度修正：Optional.empty() 是合法语义（首建会话还没写过快照），不能一并改成抛异常
        PgEngineSlotStore store = slotStore(new StubJdbc(false, false, List.of()));

        assertTrue(store.load("ops-x#1").isEmpty(), "查得到但没有行 = 无数据，应返回 empty");
    }

    @Test
    void 槽位写入失败必须上抛而不是静默丢弃() {
        PgEngineSlotStore store = slotStore(new StubJdbc(false, true, List.of()));

        assertThrows(IllegalStateException.class,
                () -> store.save(new SlotSnapshot("ops-x#1", Map.of(), 1L, null)));
    }

    // ---- 会话存储 ----

    @Test
    void 会话读取失败必须上抛而不是当成无会话() {
        // 返回 empty 会让调用方以为"没有会话"，从而以全新会话重开一个其实存在的诊断
        PgEngineSessionStore store = sessionStore(new StubJdbc(true, false, List.of()));

        assertThrows(IllegalStateException.class, () -> store.load("ops-x#1"));
    }

    @Test
    void 会话确实无记录时仍返回空() {
        PgEngineSessionStore store = sessionStore(new StubJdbc(false, false, List.of()));

        assertTrue(store.load("ops-x#1").isEmpty());
    }

    @Test
    void 会话写入失败必须上抛而不是仅存内存() {
        PgEngineSessionStore store = sessionStore(new StubJdbc(false, true, List.of()));

        assertThrows(IllegalStateException.class,
                () -> store.save(new SessionRecord(
                        "ops-x#1", null, null, "ops_diagnose", "1.0.0", "ops_diagnose_v2", "1.0.0",
                        SessionState.CREATED, null, List.of(), null, Map.of(), null, null, null, null)));
    }

    @Test
    void 两个存储的失败信息都带上会话标识便于定位() {
        PgEngineSlotStore slots = slotStore(new StubJdbc(true, false, List.of()));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> slots.load("ops-conv-42#3"));

        assertTrue(e.getMessage().contains("ops-conv-42#3"),
                "异常信息应带 sessionId，否则多实例下无法定位：" + e.getMessage());
    }
}
