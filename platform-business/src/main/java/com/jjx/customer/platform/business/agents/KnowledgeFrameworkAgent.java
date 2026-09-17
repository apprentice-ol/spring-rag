package com.jjx.customer.platform.business.agents;
import com.jjx.customer.platform.business.workflows.KnowledgeQaWorkflow;

import com.jjx.customer.platform.agent.framework.agent.Agent;
import com.jjx.customer.platform.agent.framework.capability.AgentCapability;
import com.jjx.customer.platform.agent.framework.workflow.Workflow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 知识问答 Agent（抽象侧：身份 + 能力上界 + 组合的 Workflow）。
 *
 * <p>刻意不实现执行方法：执行权在框架引擎（{@code WorkflowEngine}），
 * 因此错误处理/观测/校验/预算/元数据时序全部由引擎保证。</p>
 */
@Component
@RequiredArgsConstructor
public class KnowledgeFrameworkAgent implements Agent {

    public static final String ID = "knowledge";
    public static final String INTENT_DOMAIN = "knowledge";

    private final KnowledgeQaWorkflow workflow;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String label() {
        return "知识问答（框架主线）";
    }

    @Override
    public String description() {
        return "单次多通道检索直出答案，速度最快（eval 基线）";
    }

    @Override
    public String intentDomain() {
        return INTENT_DOMAIN;
    }

    @Override
    public Set<AgentCapability> capabilities() {
        // 五档全声明：流式/引用/精确缓存/语义缓存/检索指标（缓存两档实际生效由配置开关收窄）
        return Set.of(AgentCapability.STREAMING, AgentCapability.CITATIONS,
                AgentCapability.ANSWER_CACHE, AgentCapability.SEMANTIC_CACHE,
                AgentCapability.RETRIEVAL_METRICS);
    }

    @Override
    public Workflow workflow() {
        return workflow;
    }
}
