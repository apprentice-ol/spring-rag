package com.agentframework.engine;

import com.agentframework.engine.persistence.PersistenceManager;
import com.agentframework.runtime.persistence.SessionStore;
import com.agentframework.runtime.persistence.SlotStore;
import com.agentframework.runtime.session.DefaultSession;
import com.agentframework.runtime.session.MutableSession;
import com.agentframework.runtime.session.Session;
import com.agentframework.runtime.session.SessionRecord;
import com.agentframework.runtime.slot.SlotSnapshot;
import com.agentframework.runtime.slot.Slots;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ephemeral} 会话在<b>逐节点 checkpoint</b> 下也必须不落库。
 *
 * <p><b>真实缺陷</b>：{@code ContextManager.persist()} 里有 ephemeral 早退，但
 * {@link PersistenceManager#checkpoint} 是**直接写 store** 的——于是那个标志只挡住了
 * {@code startSession} 的初始持久化，而运行期每个节点后都会 checkpoint，照样落库。
 * 等于 <b>{@code ephemeral} 从未真正生效</b>。</p>
 *
 * <p>后果是实测过的：单轮 REST 与知识问答（都是临时会话）在引擎表里无限堆积——
 * 库里 4175 个会话中 4141 个是这类随机 UUID，占 {@code ops_engine_slots} 约 76 MB，
 * 而它们是用户以为"不落库"的请求产生的。</p>
 */
class EphemeralPersistenceTest {

    /** 记录写入的会话存储替身。 */
    private static final class RecordingSessionStore implements SessionStore {
        final List<String> saved = new ArrayList<>();

        @Override public void save(SessionRecord record) { saved.add(record.sessionId()); }
        @Override public Optional<SessionRecord> load(String sessionId) { return Optional.empty(); }
        @Override public boolean delete(String sessionId) { return false; }
        @Override public List<SessionRecord> list() { return List.of(); }
        @Override public List<SessionRecord> listByTenant(String tenantId) { return List.of(); }
    }

    /** 记录写入的槽位存储替身。 */
    private static final class RecordingSlotStore implements SlotStore {
        final List<String> saved = new ArrayList<>();

        @Override public void save(SlotSnapshot snapshot) { saved.add(snapshot.sessionId()); }
        @Override public Optional<SlotSnapshot> load(String sessionId) { return Optional.empty(); }
        @Override public boolean delete(String sessionId) { return false; }
    }

    private static Session sessionOf(String sessionId) {
        MutableSession session = new DefaultSession(sessionId, "default", "anonymous",
                "knowledge", "1.0.0", "knowledge_qa", "1.0.0");
        return session;
    }

    @Test
    void 临时会话的checkpoint不落库而普通会话照常落库() {
        RecordingSessionStore sessionStore = new RecordingSessionStore();
        RecordingSlotStore slotStore = new RecordingSlotStore();
        PersistenceManager persistence = new PersistenceManager(sessionStore, slotStore, null, null)
                .ephemeralCheck("eph-1"::equals);

        persistence.checkpoint(sessionOf("eph-1"), null, new Slots());
        persistence.checkpoint(sessionOf("ops-1"), null, new Slots());

        assertEquals(List.of("ops-1"), sessionStore.saved,
                "临时会话不该被 checkpoint 落库；普通会话必须照常落库（别把两者一起挡住）");
        assertEquals(List.of("ops-1"), slotStore.saved, "槽位快照同理");
    }

    @Test
    void 未装配判定时不跳过任何会话() {
        // 不装配 ephemeralCheck 时行为必须与改动前一致（兼容既有用法）
        RecordingSessionStore sessionStore = new RecordingSessionStore();
        PersistenceManager persistence = new PersistenceManager(sessionStore,
                new RecordingSlotStore(), null, null);

        persistence.checkpoint(sessionOf("eph-1"), null, new Slots());

        assertEquals(List.of("eph-1"), sessionStore.saved, "未装配时不跳过——默认行为不变");
    }

    @Test
    void 空会话不落库() {
        RecordingSessionStore sessionStore = new RecordingSessionStore();
        PersistenceManager persistence = new PersistenceManager(sessionStore,
                new RecordingSlotStore(), null, null);

        persistence.checkpoint(null, null, new Slots());

        assertTrue(sessionStore.saved.isEmpty());
    }
}
