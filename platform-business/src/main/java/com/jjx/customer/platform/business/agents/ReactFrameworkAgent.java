package com.jjx.customer.platform.business.agents;
import com.jjx.customer.platform.business.workflows.KnowledgeQaReactWorkflow;

import com.jjx.customer.platform.agent.framework.agent.Agent;
import com.jjx.customer.platform.agent.framework.capability.AgentCapability;
import com.jjx.customer.platform.agent.framework.workflow.Workflow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

/** 工具循环检索 Agent（react_loop 轴，框架主线）：身份与能力位同 knowledge，流程换成 LOOP 形态。 */
@Component
@RequiredArgsConstructor
public class ReactFrameworkAgent implements Agent {

    public static final String ID = "react_loop";
    public static final String INTENT_DOMAIN = "react_loop";

    private final KnowledgeQaReactWorkflow workflow;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String label() {
        return "工具循环检索（框架主线）";
    }

    @Override
    public String description() {
        return "模型自主循环：检索→评分→重排→决定何时停（工具循环节点）";
    }

    @Override
    public String intentDomain() {
        return INTENT_DOMAIN;
    }

    @Override
    public Set<AgentCapability> capabilities() {
        // 五档全声明（与 knowledge 轴同口径；缓存两档实际生效由配置开关收窄）
        return Set.of(AgentCapability.STREAMING, AgentCapability.CITATIONS,
                AgentCapability.ANSWER_CACHE, AgentCapability.SEMANTIC_CACHE,
                AgentCapability.RETRIEVAL_METRICS);
    }

    @Override
    public Workflow workflow() {
        return workflow;
    }
}
