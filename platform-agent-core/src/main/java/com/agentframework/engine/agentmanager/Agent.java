package com.agentframework.engine.agentmanager;

import com.agentframework.definition.agent.AgentDefinition;
import com.agentframework.definition.agent.ModelConfig;
import com.agentframework.definition.policy.AgentPolicies;
import com.agentframework.definition.workflow.WorkflowDefinition;

/**
 * Agent 运行实例：定义层资产在内存中的可执行形态。
 *
 * <p>由 {@code AgentManager} 创建，持有已解析的 Workflow 与策略，本身不持有运行态。</p>
 */
public interface Agent {

    /** @return Agent id */
    String id();

    /** @return Agent 版本 */
    String version();

    /** @return 原始定义 */
    AgentDefinition definition();

    /** @return 已解析的工作流定义 */
    WorkflowDefinition workflow();

    /** @return 生效的策略集合 */
    AgentPolicies policies();

    /** @return 生效的模型配置 */
    ModelConfig modelConfig();

    /** @return 实例唯一键，形如 {@code research@1.0.0} */
    default String key() {
        return id() + "@" + version();
    }
}
