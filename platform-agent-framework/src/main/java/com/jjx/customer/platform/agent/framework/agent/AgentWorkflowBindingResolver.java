package com.jjx.customer.platform.agent.framework.agent;


/**
 * 绑定解析（桥）：Agent → Workflow id。默认取 Agent 自身声明的 workflow；
 * 业务可通过配置覆盖（同一 Agent 换绑不同流程 = 换范式行为）。
 */
public interface AgentWorkflowBindingResolver {

    String workflowIdFor(Agent agent);

    /** 默认实现：用 Agent 自己声明的 Workflow（无配置覆盖时）。 */
    AgentWorkflowBindingResolver DEFAULT = agent -> agent.workflow().id();
}
