package com.agentframework.sdk;

import com.agentframework.engine.core.Engine;
import com.agentframework.engine.core.EngineBuilder;
import com.agentframework.engine.core.EngineConfig;
import com.agentframework.engine.core.RunResult;
import com.agentframework.runtime.session.Input;
import com.agentframework.runtime.session.Session;
import com.agentframework.runtime.session.StartOptions;

/**
 * 应用层门面：应用只需记住“定义 Agent → 起会话 → 运行”。
 *
 * <pre>
 * try (AgentFramework framework = AgentFramework.create().agent(agentDefinition).build()) {
 *     RunResult result = framework.run("research-assistant", "帮我调研 X");
 *     System.out.println(result.output());
 * }
 * </pre>
 */
public final class AgentFramework implements AutoCloseable {

    private final Engine engine;

    /**
     * @param engine 引擎实例
     */
    public AgentFramework(Engine engine) {
        this.engine = engine;
    }

    /** @return 默认配置下的装配器 */
    public static EngineBuilder builder() {
        return EngineBuilder.create();
    }

    /** @return 默认配置下、零外部依赖的装配器 */
    public static EngineBuilder create() {
        return EngineBuilder.create().config(EngineConfig.defaults());
    }

    /** @return 底层引擎，需要精细控制时使用 */
    public Engine engine() {
        return engine;
    }

    /**
     * 一步执行：加载 Agent → 建会话 → 运行。
     *
     * @param agentId Agent id
     * @param text    用户输入
     * @return 运行结果
     */
    public RunResult run(String agentId, String text) {
        return engine.run(agentId, Input.of(text));
    }

    /**
     * 在指定会话上运行。
     *
     * @param session 会话
     * @param text    用户输入
     * @return 运行结果
     */
    public RunResult run(Session session, String text) {
        return engine.run(session, Input.of(text));
    }

    /**
     * 恢复挂起的会话（例如人工节点答复到达）。
     *
     * @param sessionId 会话 id
     * @param text      恢复输入
     * @return 运行结果
     */
    public RunResult resume(String sessionId, String text) {
        return engine.recovery().resume(sessionId, Input.of(text));
    }

    /**
     * 新建会话。
     *
     * @param agentId Agent id
     * @param options 启动参数
     * @return 会话
     */
    public Session startSession(String agentId, StartOptions options) {
        return engine.startSession(engine.loadAgent(agentId, "latest"), options);
    }

    @Override
    public void close() {
        engine.close();
    }
}
