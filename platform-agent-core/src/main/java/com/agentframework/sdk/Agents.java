package com.agentframework.sdk;

import com.agentframework.definition.agent.AgentDefinition;
import com.agentframework.definition.agent.ModelConfig;

/**
 * Agent 定义助手：把常见定义写成一行。
 */
public final class Agents {

    private Agents() {
    }

    /**
     * @param id            Agent id
     * @param workflowId    绑定的工作流 id
     * @return Agent 定义构建器
     */
    public static AgentDefinition.Builder define(String id, String workflowId) {
        return AgentDefinition.builder(id).workflow(workflowId);
    }

    /**
     * @param id       Agent id
     * @param version  版本号
     * @param workflow 绑定的工作流 id
     * @return Agent 定义构建器
     */
    public static AgentDefinition.Builder define(String id, String version, String workflow) {
        return AgentDefinition.builder(id, version).workflow(workflow);
    }

    /**
     * @param id     Agent id
     * @param model  模型名
     * @param workflow 绑定的工作流 id
     * @return 指定模型后的构建器
     */
    public static AgentDefinition.Builder withModel(String id, String model, String workflow) {
        return AgentDefinition.builder(id)
                .workflow(workflow)
                .model(ModelConfig.of("default", model));
    }
}
