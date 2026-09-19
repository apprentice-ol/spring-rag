package com.agentframework.engine.agentmanager;

import com.agentframework.definition.agent.AgentDefinition;
import java.util.List;

/**
 * Agent 管理器：创建、加载与销毁 Agent 实例。
 *
 * <p>创建过程中完成工作流解析与策略校验，保证运行时不再出现装配错误。</p>
 */
public interface AgentManager {

    /**
     * 由定义创建 Agent 实例。
     *
     * @param definition Agent 定义
     * @return Agent 实例
     */
    Agent create(AgentDefinition definition);

    /**
     * 由定义来源加载并创建 Agent 实例。
     *
     * @param agentId Agent id
     * @param version 版本号，{@code latest} 表示最新
     * @return Agent 实例
     */
    Agent load(String agentId, String version);

    /** @return 已创建的 Agent 实例列表 */
    List<Agent> list();

    /**
     * 销毁缓存的 Agent 实例。
     *
     * @param agentId Agent id
     * @param version 版本号
     * @return 是否确实销毁
     */
    boolean destroy(String agentId, String version);
}
