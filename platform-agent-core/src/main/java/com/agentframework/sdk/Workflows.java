package com.agentframework.sdk;

import com.agentframework.definition.workflow.WorkflowBuilder;

/**
 * 工作流构建助手。
 */
public final class Workflows {

    private Workflows() {
    }

    /**
     * @param id      工作流 id
     * @param version 版本号
     * @return 工作流构建器
     */
    public static WorkflowBuilder builder(String id, String version) {
        return WorkflowBuilder.create(id, version);
    }

    /**
     * @param id 工作流 id
     * @return 版本为 1.0.0 的工作流构建器
     */
    public static WorkflowBuilder builder(String id) {
        return WorkflowBuilder.create(id, "1.0.0");
    }
}
