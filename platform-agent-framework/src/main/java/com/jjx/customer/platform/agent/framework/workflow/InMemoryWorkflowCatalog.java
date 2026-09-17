package com.jjx.customer.platform.agent.framework.workflow;

import com.jjx.customer.platform.agent.framework.workflow.Workflow;
import com.jjx.customer.platform.agent.framework.workflow.WorkflowCatalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 内存 Workflow 目录（B2 默认实现；持久化/热加载属业务侧 SPI 实现）。
 */
public class InMemoryWorkflowCatalog implements WorkflowCatalog {

    private final Map<String, Workflow> byId = new LinkedHashMap<>();

    public InMemoryWorkflowCatalog(List<Workflow> workflows) {
        if (workflows != null) {
            for (Workflow workflow : workflows) {
                Workflow previous = byId.putIfAbsent(workflow.id(), workflow);
                if (previous != null) {
                    throw new IllegalStateException("重复 Workflow id: " + workflow.id());
                }
            }
        }
    }

    @Override
    public Optional<Workflow> byId(String workflowId) {
        return Optional.ofNullable(byId.get(workflowId));
    }

    @Override
    public List<Workflow> all() {
        return List.copyOf(byId.values());
    }
}
