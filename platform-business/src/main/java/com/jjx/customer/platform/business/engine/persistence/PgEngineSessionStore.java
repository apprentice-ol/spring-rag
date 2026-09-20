package com.jjx.customer.platform.business.engine.persistence;

import com.agentframework.runtime.persistence.SessionStore;
import com.agentframework.runtime.session.SessionRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PostgreSQL 版引擎会话存储（自 agent-framework-server 移植）：整份 {@link SessionRecord}
 * 序列化为 JSONB（含 cursor 与消息历史），挂起中的诊断会话重启后可从断点恢复（O2/B1）。
 *
 * <p>与业务澄清会话表 {@code sa_agent_session} 是<b>两个东西</b>：本表持久化引擎游标
 * （问到哪个节点、循环计数），业务表持久化澄清状态机（AWAITING_USER/TTL）。</p>
 *
 * <p>建表幂等；库不可用时读写全部 warn + 降级（会话仅存内存，主链路不受影响）。</p>
 */
public class PgEngineSessionStore implements SessionStore {

    /** 引擎会话表名。 */
    public static final String TABLE = "ops_engine_session";

    private static final Logger log = LoggerFactory.getLogger(PgEngineSessionStore.class);

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS ops_engine_session (
                session_id  VARCHAR(64) PRIMARY KEY,
                record      JSONB       NOT NULL,
                updated_at  TIMESTAMP   NOT NULL DEFAULT NOW()
            )""";

    private final JdbcTemplate jdbc;

    private final ObjectMapper objectMapper;

    /**
     * @param jdbc         数据访问模板
     * @param objectMapper JSON 序列化
     */
    public PgEngineSessionStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        try {
            jdbc.execute(DDL);
            log.info("[engine-store] 引擎会话表就绪：{}", TABLE);
        } catch (Exception e) {
            log.warn("[engine-store] 引擎会话表初始化失败（跨重启恢复不可用）：{}", e.getMessage());
        }
    }

    @Override
    public void save(SessionRecord record) {
        try {
            jdbc.update("INSERT INTO " + TABLE + " (session_id, record, updated_at) VALUES (?, ?::jsonb, NOW()) "
                            + "ON CONFLICT (session_id) DO UPDATE SET record = EXCLUDED.record, updated_at = NOW()",
                    record.sessionId(), toJson(record));
        } catch (Exception e) {
            // 写失败 = 会话快照没落库，跨重启/跨实例恢复会读到旧状态；静默会让损坏延迟暴露
            log.error("[engine-store] 引擎会话保存失败：sessionId={} {}", record.sessionId(), e.getMessage());
            throw new IllegalStateException("引擎会话快照保存失败: " + record.sessionId(), e);
        }
    }

    @Override
    public Optional<SessionRecord> load(String sessionId) {
        List<SessionRecord> found;
        try {
            found = jdbc.query(
                    "SELECT record FROM " + TABLE + " WHERE session_id = ?",
                    (rs, rowNum) -> readRecord(rs.getString("record")),
                    sessionId);
        } catch (Exception e) {
            // 读不到 ≠ 没有（同 PgEngineSlotStore）：返回 empty 会被调用方当成"无会话"，
            // 从而以全新会话重开一个其实存在的诊断
            log.error("[engine-store] 引擎会话读取失败：sessionId={} {}", sessionId, e.getMessage());
            throw new IllegalStateException("引擎会话快照读取失败: " + sessionId, e);
        }
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public List<SessionRecord> listByTenant(String tenantId) {
        return list().stream()
                .filter(record -> record.tenantId() != null && record.tenantId().equals(tenantId))
                .toList();
    }

    @Override
    public List<SessionRecord> list() {
        try {
            return jdbc.query("SELECT record FROM " + TABLE + " ORDER BY updated_at DESC",
                    (rs, rowNum) -> readRecord(rs.getString("record")));
        } catch (Exception e) {
            log.warn("[engine-store] 引擎会话列举失败：{}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public boolean delete(String sessionId) {
        try {
            return jdbc.update("DELETE FROM " + TABLE + " WHERE session_id = ?", sessionId) > 0;
        } catch (Exception e) {
            log.warn("[engine-store] 引擎会话删除失败：sessionId={} {}", sessionId, e.getMessage());
            return false;
        }
    }

    private SessionRecord readRecord(String json) {
        try {
            return objectMapper.readValue(json, SessionRecord.class);
        } catch (Exception e) {
            throw new IllegalStateException("引擎会话反序列化失败", e);
        }
    }

    private String toJson(SessionRecord record) {
        try {
            return objectMapper.writeValueAsString(record);
        } catch (Exception e) {
            throw new IllegalStateException("引擎会话序列化失败", e);
        }
    }
}
