package com.jjx.customer.platform.agent.framework.plan;

import com.jjx.customer.platform.agent.framework.agent.Agent;
import com.jjx.customer.platform.agent.framework.agent.AgentRequest;
import com.jjx.customer.platform.agent.framework.capability.AgentCapability;
import com.jjx.customer.platform.agent.framework.prompt.PromptSnapshot;
import com.jjx.customer.platform.agent.framework.result.ExecutionFingerprint;
import com.jjx.customer.platform.agent.framework.workflow.Workflow;

import java.util.Map;
import java.util.Set;

/**
 * 执行计划（契约层）：路由 + 绑定 + 能力三方求解 + prompt 快照 + 执行指纹的装配产物。
 *
 * <p>驱动层只消费本对象——它拿到的每一项都已被校验过（不静默降级）。</p>
 *
 * @param routeNote 路由摘要（命中策略/域/目标 agent；路由是一个 trace step。可空＝未经路由直配）
 */
public record ExecutionPlan(AgentRequest request,
                            Agent agent,
                            Workflow workflow,
                            Set<AgentCapability> capabilities,
                            PromptSnapshot promptSnapshot,
                            ExecutionFingerprint fingerprint,
                            Map<String, Object> prefill,
                            String routeNote) {

    public ExecutionPlan {
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        prefill = prefill == null ? Map.of() : Map.copyOf(prefill);
    }

    /**
     *
     * @param request
     * @param agent
     * @param workflow
     * @param capabilities
     * @param promptSnapshot
     * @param fingerprint
     * @param prefill
     */
    public ExecutionPlan(AgentRequest request, Agent agent, Workflow workflow,
                         Set<AgentCapability> capabilities, PromptSnapshot promptSnapshot,
                         ExecutionFingerprint fingerprint, Map<String, Object> prefill) {
        this(request, agent, workflow, capabilities, promptSnapshot, fingerprint, prefill, null);
    }
}
