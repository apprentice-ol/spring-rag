package com.agentframework.engine.contextmanager;

import com.agentframework.engine.agentmanager.Agent;
import com.agentframework.runtime.session.Session;
import com.agentframework.runtime.session.SessionRecord;
import com.agentframework.runtime.session.StartOptions;
import com.agentframework.runtime.slot.Slots;
import com.agentframework.runtime.slot.SlotSnapshot;
import com.agentframework.runtime.workspace.Workspace;
import com.agentframework.runtime.workspace.WorkspaceTemplate;
import java.util.Map;
import java.util.Optional;

/**
 * 上下文管理器：管理 Session / Workspace / Slot 三者的生命周期。
 *
 * <p>关系固定为：一个会话拥有一个工作区与一组槽位；工作区可被多个会话共享。</p>
 */
public interface ContextManager {

    /**
     * 创建会话并初始化其工作区与槽位。
     *
     * @param agent   Agent 实例
     * @param options 启动参数
     * @return 会话
     */
    Session createSession(Agent agent, StartOptions options);

    /**
     * @param sessionId 会话 id
     * @return 工作区，未创建返回 null
     */
    Workspace workspace(String sessionId);

    /**
     * @param sessionId 会话 id
     * @return 槽位容器
     */
    Slots slots(String sessionId);

    /**
     * 持久化会话与槽位。
     *
     * @param session 会话
     */
    void persist(Session session);

    /**
     * 由存储还原会话。
     *
     * @param sessionId 会话 id
     * @return 会话
     */
    Optional<Session> load(String sessionId);

    /**
     * 用快照整体替换会话与槽位：回滚时使用，属于"整份替换"而非增量恢复。
     *
     * @param record 会话记录快照
     * @param slots  槽位快照，可为 null 表示只恢复会话
     * @return 恢复后的会话
     */
    Session restore(SessionRecord record, SlotSnapshot slots);

    /**
     * 释放会话占用的内存资源（不影响已持久化的数据）。
     *
     * @param sessionId 会话 id
     */
    void release(String sessionId);

    /**
     * 注册工作区模板。
     *
     * @param template 模板
     * @return 当前管理器
     */
    ContextManager registerTemplate(WorkspaceTemplate template);

    /** @return 已注册的模板 */
    Map<String, WorkspaceTemplate> templates();

    /**
     * 判断会话是否为临时会话（不落库）。
     *
     * @param sessionId 会话 id
     * @return 是否临时
     */
    boolean ephemeral(String sessionId);
}
