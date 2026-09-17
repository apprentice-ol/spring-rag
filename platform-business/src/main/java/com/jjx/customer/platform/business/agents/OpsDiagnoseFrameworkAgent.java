package com.jjx.customer.platform.business.agents;
import com.jjx.customer.platform.business.workflows.OpsDiagnoseWorkflow;

import com.jjx.customer.platform.agent.framework.agent.Agent;
import com.jjx.customer.platform.agent.framework.capability.AgentCapability;
import com.jjx.customer.platform.agent.framework.workflow.Workflow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

/** 运维诊断 Agent（框架主线）：身份 + 能力上界（直答交付，无流式/引用能力）+ 组合的流程。 */
@Component
@RequiredArgsConstructor
public class OpsDiagnoseFrameworkAgent implements Agent {

    public static final String ID = "ops_diagnose";
    public static final String INTENT_DOMAIN = "ops_diagnose";

    private final OpsDiagnoseWorkflow workflow;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String label() {
        return "运维诊断（框架主线）";
    }

    @Override
    public String description() {
        return "先追问补齐槽位，再按固定排查骨架分阶段调工具（查日志→查文档/生成报文→确定性校验）";
    }

    @Override
    public String intentDomain() {
        return INTENT_DOMAIN;
    }

    @Override
    public Set<AgentCapability> capabilities() {
        return Set.of();
    }

    @Override
    public Workflow workflow() {
        return workflow;
    }
}
