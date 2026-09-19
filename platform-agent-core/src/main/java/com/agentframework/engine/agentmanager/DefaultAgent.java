package com.agentframework.engine.agentmanager;

import com.agentframework.definition.agent.AgentDefinition;
import com.agentframework.definition.agent.ModelConfig;
import com.agentframework.definition.policy.AgentPolicies;
import com.agentframework.definition.workflow.WorkflowDefinition;
import java.util.Objects;

/**
 * {@link Agent} 的默认实现：定义与已解析工作流的不可变组合。
 *
 * @param definition Agent 定义
 * @param workflow   已解析的工作流定义
 */
public record DefaultAgent(AgentDefinition definition, WorkflowDefinition workflow) implements Agent {

    public DefaultAgent {
        Objects.requireNonNull(definition, "Agent 定义不能为空");
        Objects.requireNonNull(workflow, "Agent 必须绑定工作流");
    }

    @Override
    public String id() {
        return definition.id();
    }

    @Override
    public String version() {
        return definition.version();
    }

    @Override
    public AgentPolicies policies() {
        return definition.policies();
    }

    @Override
    public ModelConfig modelConfig() {
        return definition.modelConfig();
    }
}
