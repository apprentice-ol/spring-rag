package com.agentframework.engine.core;

import com.agentframework.crosscutting.metrics.Metrics;
import com.agentframework.crosscutting.trace.Tracer;
import com.agentframework.definition.agent.AgentDefinition;
import com.agentframework.engine.agentmanager.Agent;
import com.agentframework.engine.agentmanager.AgentManager;
import com.agentframework.engine.agentmanager.DefinitionSource;
import com.agentframework.engine.contextmanager.ContextManager;
import com.agentframework.engine.persistence.PersistenceManager;
import com.agentframework.engine.persistence.RollbackResult;
import com.agentframework.runtime.persistence.CheckpointEntry;
import com.agentframework.engine.pluginruntime.PluginRuntime;
import com.agentframework.engine.policy.RegionMetrics;
import com.agentframework.engine.promptmanager.PromptManager;
import com.agentframework.engine.scheduling.RecoveryManager;
import com.agentframework.engine.toolexecutor.ToolRegistry;
import com.agentframework.extension.registry.ExtensionRegistry;
import com.agentframework.runtime.event.EventBus;
import com.agentframework.runtime.session.Input;
import com.agentframework.runtime.session.Session;
import com.agentframework.runtime.session.StartOptions;
import java.util.Map;

/**
 * 引擎：框架内核，负责创建、调度、注入与持久化。
 *
 * <p>应用层只与本接口交互：Agent 是配置主体，Session 是运行态，RunResult 是交付物。</p>
 */
public interface Engine extends AutoCloseable {

    /**
     * 由定义创建 Agent 实例。
     *
     * @param definition Agent 定义
     * @return Agent 实例
     */
    Agent createAgent(AgentDefinition definition);

    /**
     * 由定义来源加载 Agent。
     *
     * @param agentId Agent id
     * @param version 版本号，{@code latest} 表示最新
     * @return Agent 实例
     */
    Agent loadAgent(String agentId, String version);

    /**
     * 创建会话。
     *
     * @param agent   Agent 实例
     * @param options 启动参数
     * @return 会话
     */
    Session startSession(Agent agent, StartOptions options);

    /**
     * 在会话上执行一次运行。
     *
     * @param session 会话
     * @param input   输入
     * @return 运行结果
     */
    RunResult run(Session session, Input input);

    /**
     * 从游标处恢复执行。
     *
     * @param session 会话（通常由存储还原，状态为挂起）
     * @param input   恢复输入，例如人工答复
     * @return 运行结果
     */
    RunResult resume(Session session, Input input);

    /**
     * 取消会话。
     *
     * @param session 会话
     */
    void cancel(Session session);

    /**
     * 一步完成“加载 Agent → 建会话 → 运行”。
     *
     * @param agentId Agent id
     * @param input   输入
     * @return 运行结果
     */
    RunResult run(String agentId, Input input);

    /** @return 引擎配置 */
    EngineConfig config();

    /** @return Agent 管理器 */
    AgentManager agents();

    /** @return 上下文管理器 */
    ContextManager contexts();

    /** @return 工具注册表 */
    ToolRegistry tools();

    /** @return Prompt 管理器 */
    PromptManager prompts();

    /** @return 定义来源 */
    DefinitionSource definitions();

    /** @return 扩展注册表 */
    ExtensionRegistry extensions();

    /** @return 追踪器 */
    Tracer tracer();

    /** @return 指标采集器 */
    Metrics metrics();

    /** @return 事件总线 */
    EventBus events();

    /** @return 插件运行时 */
    PluginRuntime plugins();

    /** @return 恢复管理器 */
    RecoveryManager recovery();

    /** @return 持久化管理器 */
    PersistenceManager persistence();

    /**
     * 按会话读取 Region 指标。
     *
     * @param sessionId 会话 id
     * @return 区域 id 到指标的映射，未声明 Region 时为空表
     */
    Map<String, RegionMetrics> regionMetrics(String sessionId);

    /**
     * 读取会话的检查点历史。
     *
     * @param sessionId 会话 id
     * @return 按步号升序的检查点，无记录时为空列表
     */
    java.util.List<CheckpointEntry> history(String sessionId);

    /**
     * 回滚到指定步。
     *
     * <p>只恢复引擎内部状态；已发生的工具调用、人工审批、外部写入与模型费用不可回滚。</p>
     *
     * @param sessionId 会话 id
     * @param toStep    目标步号
     * @return 回滚结果
     */
    RollbackResult rollback(String sessionId, int toStep);

    @Override
    void close();
}
