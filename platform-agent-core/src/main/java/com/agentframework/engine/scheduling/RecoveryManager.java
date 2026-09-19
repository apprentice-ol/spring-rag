package com.agentframework.engine.scheduling;

import com.agentframework.engine.contextmanager.ContextManager;
import com.agentframework.engine.core.Engine;
import com.agentframework.engine.core.RunResult;
import com.agentframework.runtime.session.Input;
import com.agentframework.runtime.session.Session;
import com.agentframework.runtime.session.SessionState;
import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 恢复管理器：把“断点续跑”变成可调用的能力。
 *
 * <p>典型场景：进程重启后扫描未完成的会话并继续执行；人工节点的答复到达后从挂起点恢复。</p>
 */
public final class RecoveryManager {

    private final ContextManager contexts;
    private final Scheduler scheduler;
    private Engine engine;

    /**
     * @param contexts  上下文管理器
     * @param scheduler 调度器，可为 null
     */
    public RecoveryManager(ContextManager contexts, Scheduler scheduler) {
        this.contexts = contexts;
        this.scheduler = scheduler;
    }

    /**
     * 绑定引擎实例（由引擎构建器在装配完成后调用）。
     *
     * @param engine 引擎
     */
    public void attach(Engine engine) {
        this.engine = engine;
    }

    /**
     * 恢复会话，使用空输入。
     *
     * @param sessionId 会话 id
     * @return 运行结果
     */
    public RunResult resume(String sessionId) {
        return resume(sessionId, Input.empty());
    }

    /**
     * 恢复会话。
     *
     * @param sessionId 会话 id
     * @param input     恢复时的输入（例如人工答复）
     * @return 运行结果
     */
    public RunResult resume(String sessionId, Input input) {
        requireEngine();
        Session session = contexts.load(sessionId)
                .orElseThrow(() -> new NoSuchElementException("会话不存在：" + sessionId));
        return engine.resume(session, input == null ? Input.empty() : input);
    }

    /**
     * 延迟恢复会话。
     *
     * @param sessionId 会话 id
     * @param delay     延迟
     * @param input     恢复输入
     * @return 调度句柄
     */
    public Scheduler.ScheduledHandle scheduleResume(String sessionId, Duration delay, Input input) {
        if (scheduler == null) {
            throw new IllegalStateException("未配置调度器，无法安排延迟恢复");
        }
        return scheduler.submit("resume:" + sessionId, delay, () -> resume(sessionId, input));
    }

    /**
     * 列出所有可恢复的会话（处于挂起状态）。
     *
     * @param sessionIds 候选会话 id
     * @return 挂起中的会话 id
     */
    public List<String> resumable(List<String> sessionIds) {
        if (sessionIds == null) {
            return List.of();
        }
        return sessionIds.stream()
                .map(contexts::load)
                .flatMap(java.util.Optional::stream)
                .filter(session -> session.state() == SessionState.SUSPENDED)
                .map(Session::id)
                .toList();
    }

    /** 校验引擎是否已绑定。 */
    private void requireEngine() {
        if (engine == null) {
            throw new IllegalStateException("恢复管理器尚未绑定引擎实例");
        }
    }
}
