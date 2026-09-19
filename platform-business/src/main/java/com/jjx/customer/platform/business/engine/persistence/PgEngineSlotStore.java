package com.jjx.customer.platform.business.engine.persistence;

import com.agentframework.runtime.persistence.SlotStore;
import com.agentframework.runtime.slot.SlotSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PostgreSQL 版引擎槽位存储（自 agent-framework-server 移植）：与 {@link PgEngineSessionStore}
 * 配套——重启后从存储还原会话时，槽位快照（诊断已收集信息、阶段产出、llm_calls 计数）
 * 一并还原，恢复不问已填项（O2）。
 */
public class PgEngineSlotStore implements SlotStore {

    /** 槽位快照表名。 */
    public static final String TABLE = "ops_engine_slots";

    private static final Logger log = LoggerFactory.getLogger(PgEngineSlotStore.class);

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS ops_engine_slots (
                session_id  VARCHAR(64) PRIMARY KEY,
                snapshot    JSONB       NOT NULL,
                updated_at  TIMESTAMP   NOT NULL DEFAULT NOW()
            )""";

    private final JdbcTemplate jdbc;

    private final ObjectMapper objectMapper;

    /**
     * @param jdbc         数据访问模板
     * @param objectMapper JSON 序列化
     */
    public PgEngineSlotStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        try {
            jdbc.execute(DDL);
            log.info("[engine-store] 引擎槽位表就绪：{}", TABLE);
        } catch (Exception e) {
            log.warn("[engine-store] 引擎槽位表初始化失败：{}", e.getMessage());
        }
    }

    @Override
    public void save(SlotSnapshot snapshot) {
        try {
            jdbc.update("INSERT INTO " + TABLE + " (session_id, snapshot, updated_at) VALUES (?, ?::jsonb, NOW()) "
                            + "ON CONFLICT (session_id) DO UPDATE SET snapshot = EXCLUDED.snapshot, updated_at = NOW()",
                    snapshot.sessionId(), toJson(snapshot));
        } catch (Exception e) {
            log.warn("[engine-store] 引擎槽位保存失败：sessionId={} {}", snapshot.sessionId(), e.getMessage());
        }
    }

    @Override
    public Optional<SlotSnapshot> load(String sessionId) {
        try {
            List<SlotSnapshot> found = jdbc.query(
                    "SELECT snapshot FROM " + TABLE + " WHERE session_id = ?",
                    (rs, rowNum) -> readSnapshot(rs.getString("snapshot")),
                    sessionId);
            return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
        } catch (Exception e) {
            log.warn("[engine-store] 引擎槽位读取失败：sessionId={} {}", sessionId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public boolean delete(String sessionId) {
        try {
            return jdbc.update("DELETE FROM " + TABLE + " WHERE session_id = ?", sessionId) > 0;
        } catch (Exception e) {
            log.warn("[engine-store] 引擎槽位删除失败：sessionId={} {}", sessionId, e.getMessage());
            return false;
        }
    }

    private SlotSnapshot readSnapshot(String json) {
        try {
            return objectMapper.readValue(json, SlotSnapshot.class);
        } catch (Exception e) {
            throw new IllegalStateException("引擎槽位快照反序列化失败", e);
        }
    }

    private String toJson(SlotSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new IllegalStateException("引擎槽位快照序列化失败", e);
        }
    }
}
