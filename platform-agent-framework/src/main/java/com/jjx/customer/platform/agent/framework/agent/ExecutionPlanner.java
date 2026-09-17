package com.jjx.customer.platform.agent.framework.agent;

import com.jjx.customer.platform.agent.framework.agent.Agent;
import com.jjx.customer.platform.agent.framework.agent.AgentRequest;
import com.jjx.customer.platform.agent.framework.capability.AgentCapability;
import com.jjx.customer.platform.agent.framework.capability.AgentCapabilityResolution;
import com.jjx.customer.platform.agent.framework.prompt.PromptSnapshot;
import com.jjx.customer.platform.agent.framework.prompt.PromptSnapshotSource;
import com.jjx.customer.platform.agent.framework.plan.ExecutionPlan;
import com.jjx.customer.platform.agent.framework.result.ExecutionFingerprint;
import com.jjx.customer.platform.agent.framework.workflow.Workflow;
import com.jjx.customer.platform.agent.framework.workflow.WorkflowCatalog;
import com.jjx.customer.platform.agent.framework.agent.AgentWorkflowBindingResolver;
import com.jjx.customer.platform.agent.framework.capability.CapabilityConfig;
import com.jjx.customer.platform.agent.framework.route.RouteContext;
import com.jjx.customer.platform.agent.framework.route.RouteDecision;
import com.jjx.customer.platform.agent.framework.route.RouteTable;

import java.util.Set;
import java.util.Map;

/**
 * 执行计划装配器：路由 → 绑定解析 → 能力三方求解 → prompt 快照 → 执行指纹。
 *
 * <p>所有"缺失/不匹配"都在这里失败（装配期语义），驱动层拿到的都是已校验输入。</p>
 */
public class ExecutionPlanner {

    private final RouteTable routeTable;
    private final WorkflowCatalog workflowCatalog;
    private final AgentWorkflowBindingResolver bindingResolver;
    private final CapabilityConfig capabilityConfig;
    private final PromptSnapshotSource promptSnapshotSource;

    public ExecutionPlanner(RouteTable routeTable,
                            WorkflowCatalog workflowCatalog,
                            AgentWorkflowBindingResolver bindingResolver,
                            CapabilityConfig capabilityConfig,
                            PromptSnapshotSource promptSnapshotSource) {
        this.routeTable = routeTable;
        this.workflowCatalog = workflowCatalog;
        this.bindingResolver = bindingResolver;
        this.capabilityConfig = capabilityConfig;
        this.promptSnapshotSource = promptSnapshotSource;
    }

    /** 装配执行计划；任一步不满足契约即抛错。 */
    public ExecutionPlan plan(AgentRequest request) {
        Object domain = request.attributes().get(AgentRequest.ATTR_INTENT_DOMAIN);
        String intentDomain = domain == null ? null : String.valueOf(domain);
        RouteDecision decision = routeTable.route(new RouteContext(request, intentDomain));
        String routeNote = "domain=" + intentDomain + " → agent=" + decision.agent().id()
                + "（via " + decision.via() + "）";
        return planFor(decision.agent(), request, decision.prefill(), routeNote);
    }

    /** 指定 Agent 直接装配（子 Agent 重入用：不再路由）。 */
    public ExecutionPlan planFor(Agent agent, AgentRequest request) {
        return planFor(agent, request, Map.of(), null);
    }

    private ExecutionPlan planFor(Agent agent, AgentRequest request,
                                  Map<String, Object> prefill, String routeNote) {
        String workflowId = bindingResolver.workflowIdFor(agent);
        Workflow workflow = workflowCatalog.byId(workflowId)
                .orElseThrow(() -> new IllegalStateException(
                        "绑定的 Workflow 未注册: agent=" + agent.id() + " workflow=" + workflowId));

        Set<AgentCapability> enabled = capabilityConfig.enabledFor(agent).orElse(null);
        Set<AgentCapability> effective = AgentCapabilityResolution.resolve(
                agent.capabilities(), workflow.suppliedCapabilities(), enabled);

        PromptSnapshot snapshot = promptSnapshotSource.snapshotFor(agent, workflow);
        ExecutionFingerprint fingerprint = new ExecutionFingerprint(
                agent.id(), workflow.id(), snapshot.contentHash(), snapshot.releasesSpec());

        return new ExecutionPlan(request, agent, workflow, effective, snapshot, fingerprint,
                prefill, routeNote);
    }
}
