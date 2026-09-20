package com.agentframework.engine.contextmanager;

import com.agentframework.engine.agentmanager.Agent;
import com.agentframework.infra.storage.InMemoryWorkspace;
import com.agentframework.runtime.persistence.SessionStore;
import com.agentframework.runtime.persistence.SlotStore;
import com.agentframework.runtime.persistence.WorkspaceSnapshotStore;
import com.agentframework.runtime.session.DefaultSession;
import com.agentframework.runtime.session.MutableSession;
import com.agentframework.runtime.session.Session;
import com.agentframework.runtime.session.SessionState;
import com.agentframework.runtime.session.SessionRecord;
import com.agentframework.runtime.session.StartOptions;
import com.agentframework.runtime.slot.Slots;
import com.agentframework.runtime.slot.SlotSnapshot;
import com.agentframework.runtime.workspace.Workspace;
import com.agentframework.runtime.workspace.WorkspaceTemplate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认上下文管理器：内存态会话 + 可插拔存储。
 *
 * <p>会话、槽位、工作区分别以 sessionId 为键索引；持久化委托给运行态层定义的存储接口，
 * 因此换成数据库或文件存储时本类无需改动。</p>
 */
public final class DefaultContextManager implements ContextManager {

    private final SessionStore sessionStore;
    private final SlotStore slotStore;
    /**
     * 工作区快照存储。
     *
     * <p>本类**当前不写它**（{@code release} 曾顺带写空快照，已移除——见该方法说明）。
     * 保留构造参数是为了不动内核公开构造签名；{@code PersistenceManager} 仍是它的消费方。</p>
     */
    private final WorkspaceSnapshotStore snapshotStore;
    private final Map<String, WorkspaceTemplate> templates = new LinkedHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Workspace> workspaces = new ConcurrentHashMap<>();
    private final Map<String, Slots> slots = new ConcurrentHashMap<>();
    private final Set<String> ephemeral = ConcurrentHashMap.newKeySet();

    /**
     * @param sessionStore  会话存储
     * @param slotStore     槽位存储
     * @param snapshotStore 工作区快照存储，可为 null
     */
    public DefaultContextManager(SessionStore sessionStore, SlotStore slotStore,
            WorkspaceSnapshotStore snapshotStore) {
        this.sessionStore = sessionStore;
        this.slotStore = slotStore;
        this.snapshotStore = snapshotStore;
        registerTemplate(WorkspaceTemplate.empty());
    }

    @Override
    public Session createSession(Agent agent, StartOptions options) {
        StartOptions effectiveStartOptions = options == null ? StartOptions.defaults() : options;
        String sessionId = effectiveStartOptions.sessionId() == null || effectiveStartOptions.sessionId().isBlank()
                ? UUID.randomUUID().toString()
                : effectiveStartOptions.sessionId();
        MutableSession session = new DefaultSession(sessionId, effectiveStartOptions.effectiveTenantId(),
                effectiveStartOptions.effectiveUserId(), agent.id(), agent.version(), agent.workflow().id(),
                agent.workflow().version());
        session.state(SessionState.CREATED);
        session.traceId(effectiveStartOptions.traceId() == null
                ? UUID.randomUUID().toString().replace("-", "")
                : effectiveStartOptions.traceId());
        session.extensionVersion("framework", "0.1.0");

        WorkspaceTemplate template = templates.getOrDefault(agent.definition().workspaceTemplate(),
                templates.get("default"));
        Workspace workspace = InMemoryWorkspace.fromTemplate(
                effectiveStartOptions.workspaceId() == null ? "ws-" + sessionId : effectiveStartOptions.workspaceId(),
                sessionId, template);
        Slots slotContainer = new Slots(agent.workflow().slotsSchema().applyDefaults(effectiveStartOptions.slots()));

        sessions.put(sessionId, session);
        workspaces.put(sessionId, workspace);
        slots.put(sessionId, slotContainer);
        if (effectiveStartOptions.ephemeral()) {
            ephemeral.add(sessionId);
        }
        return session;
    }

    @Override
    public Workspace workspace(String sessionId) {
        return workspaces.get(sessionId);
    }

    @Override
    public Slots slots(String sessionId) {
        return slots.computeIfAbsent(sessionId, ignored -> new Slots());
    }

    @Override
    public void persist(Session session) {
        if (session == null || ephemeral.contains(session.id())) {
            return;
        }
        sessionStore.save(session.toRecord());
        Slots container = slots.get(session.id());
        if (container != null) {
            slotStore.save(container.snapshot(session.id()));
        }
    }

    @Override
    public Optional<Session> load(String sessionId) {
        Session cached = sessions.get(sessionId);
        if (cached != null) {
            return Optional.of(cached);
        }
        return sessionStore.load(sessionId).map(record -> {
            Session session = DefaultSession.fromRecord(record);
            // 先取槽位快照再写缓存：槽位读取失败时直接上抛，不留"半个会话"。
            // 若先行 sessions.put，失败后缓存里会留下一个无槽位的会话——
            // 后续 load 命中缓存直接返回，slots() 又 computeIfAbsent 造空容器，
            // 于是"读失败"被静默转成"没有状态"，正是本类要杜绝的情形。
            Slots restored = slotStore.load(sessionId).map(Slots::fromSnapshot).orElse(null);
            sessions.put(sessionId, session);
            if (restored != null) {
                slots.put(sessionId, restored);
            }
            workspaces.computeIfAbsent(sessionId, ignored ->
                    InMemoryWorkspace.fromTemplate("ws-" + sessionId, sessionId,
                            templates.getOrDefault("default", WorkspaceTemplate.empty())));
            return session;
        });
    }

    /**
     * 释放会话占用的内存资源（不影响已持久化的数据）。
     *
     * <p><b>不再顺带写工作区快照</b>：本方法的语义是"释放"，而快照写进去没人读——
     * 全仓 {@code WorkspaceSnapshotStore.load/latest/list} 零调用点，默认实现
     * （{@link com.agentframework.infra.storage.InMemoryWorkspaceSnapshotStore}）
     * 又是按 workspaceId 无限追加的进程内 map。留着那行会让"释放内存"变成
     * "把数据从三个 map 搬到一个只增不减的 map 里"。</p>
     *
     * <p>工作区若要持久化，应当是显式的持久化动作，而不是挂在释放路径上的副作用。</p>
     */
    @Override
    public void release(String sessionId) {
        workspaces.remove(sessionId);
        sessions.remove(sessionId);
        slots.remove(sessionId);
        ephemeral.remove(sessionId);
    }

    @Override
    public Session restore(SessionRecord record, SlotSnapshot snapshot) {
        Session restored = DefaultSession.fromRecord(record);
        String sessionId = restored.id();
        sessions.put(sessionId, restored);
        if (snapshot != null) {
            slots.put(sessionId, Slots.fromSnapshot(snapshot));
        }
        workspaces.computeIfAbsent(sessionId, ignored ->
                InMemoryWorkspace.fromTemplate("ws-" + sessionId, sessionId,
                        templates.getOrDefault("default", WorkspaceTemplate.empty())));
        sessionStore.save(restored.toRecord());
        Slots container = slots.get(sessionId);
        if (container != null) {
            slotStore.save(container.snapshot(sessionId));
        }
        return restored;
    }

    @Override
    public ContextManager registerTemplate(WorkspaceTemplate template) {
        if (template != null) {
            templates.put(template.id(), template);
        }
        return this;
    }

    @Override
    public Map<String, WorkspaceTemplate> templates() {
        return Map.copyOf(templates);
    }

    @Override
    public boolean ephemeral(String sessionId) {
        return ephemeral.contains(sessionId);
    }

    /** @return 当前驻留在内存中的会话数量 */
    public int activeSessions() {
        return sessions.size();
    }
}
