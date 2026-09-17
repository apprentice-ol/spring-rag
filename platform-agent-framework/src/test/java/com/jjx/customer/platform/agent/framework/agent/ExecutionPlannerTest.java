package com.jjx.customer.platform.agent.framework.agent;

import com.jjx.customer.platform.agent.framework.agent.Agent;
import com.jjx.customer.platform.agent.framework.agent.AgentRequest;
import com.jjx.customer.platform.agent.framework.capability.AgentCapability;
import com.jjx.customer.platform.agent.framework.capability.AgentCapabilityContractException;
import com.jjx.customer.platform.agent.framework.prompt.PromptSnapshot;
import com.jjx.customer.platform.agent.framework.prompt.PromptSnapshotSource;
import com.jjx.customer.platform.agent.framework.plan.ExecutionPlan;
import com.jjx.customer.platform.agent.framework.result.ExecutionFingerprint;
import com.jjx.customer.platform.agent.framework.node.NodeKind;
import com.jjx.customer.platform.agent.framework.workflow.SlotSpec;
import com.jjx.customer.platform.agent.framework.workflow.WorkflowStageSpec;
import com.jjx.customer.platform.agent.framework.workflow.Workflow;
import com.jjx.customer.platform.agent.framework.agent.AgentWorkflowBindingResolver;
import com.jjx.customer.platform.agent.framework.capability.CapabilityConfig;
import com.jjx.customer.platform.agent.framework.workflow.InMemoryWorkflowCatalog;
import com.jjx.customer.platform.agent.framework.route.RouteContext;
import com.jjx.customer.platform.agent.framework.route.RouteDecision;
import com.jjx.customer.platform.agent.framework.route.RouteStrategy;
import com.jjx.customer.platform.agent.framework.route.RouteTable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionPlannerTest {

    private static final Workflow WORKFLOW = new Workflow() {
        @Override
        public String id() {
            return "wf_target";
        }

        @Override
        public List<SlotSpec> slots() {
            return List.of();
        }

        @Override
        public List<WorkflowStageSpec> stages() {
            return List.of(new WorkflowStageSpec("stage1", NodeKind.DETERMINISTIC, "wf_target/stage1",
                    List.of("tool_a"), null));
        }

        @Override
        public Set<AgentCapability> suppliedCapabilities() {
            return Set.of(AgentCapability.STREAMING, AgentCapability.CITATIONS);
        }
    };

    private static Agent agent(Set<AgentCapability> capabilities, Workflow workflow) {
        return new Agent() {
            @Override
            public String id() {
                return "agent_a";
            }

            @Override
            public String intentDomain() {
                return "demo";
            }

            @Override
            public Set<AgentCapability> capabilities() {
                return capabilities;
            }

            @Override
            public Workflow workflow() {
                return workflow;
            }
        };
    }

    private static ExecutionPlanner planner(Agent agent, CapabilityConfig config, AgentWorkflowBindingResolver resolver) {
        RouteStrategy strategy = new RouteStrategy() {
            @Override
            public int priority() {
                return 10;
            }

            @Override
            public Optional<RouteDecision> match(RouteContext context) {
                return Optional.of(RouteDecision.of(agent));
            }
        };
        PromptSnapshotSource source = (a, w) -> new PromptSnapshot(
                java.util.Map.of("demo/key", "内容"), "demo@r1", "hash-1", "1:1");
        return new ExecutionPlanner(new RouteTable(List.of(strategy)),
                new InMemoryWorkflowCatalog(List.of(WORKFLOW)), resolver, config, source);
    }

    @Test
    void 装配成功_能力取交集_指纹来自快照() {
        Agent agent = agent(Set.of(AgentCapability.STREAMING, AgentCapability.CITATIONS), WORKFLOW);
        ExecutionPlan plan = planner(agent, CapabilityConfig.DEFAULT, AgentWorkflowBindingResolver.DEFAULT)
                .plan(AgentRequest.of("问题"));

        assertEquals(Set.of(AgentCapability.STREAMING, AgentCapability.CITATIONS), plan.capabilities());
        ExecutionFingerprint fp = plan.fingerprint();
        assertEquals("agent_a", fp.agentId());
        assertEquals("wf_target", fp.workflowId());
        assertEquals("hash-1", fp.promptHash());
        assertEquals("1:1", fp.releasesSpec());
    }

    @Test
    void 配置收窄能力_生效集合变小() {
        Agent agent = agent(Set.of(AgentCapability.STREAMING, AgentCapability.CITATIONS), WORKFLOW);
        // 按「声明集 − 禁用集」收窄（实现拿得到 Agent 声明）
        CapabilityConfig config = a -> Optional.of(Set.of(AgentCapability.STREAMING));

        ExecutionPlan plan = planner(agent, config, AgentWorkflowBindingResolver.DEFAULT).plan(AgentRequest.of("问题"));

        assertEquals(Set.of(AgentCapability.STREAMING), plan.capabilities());
    }

    @Test
    void 供给缺失_装配期报错() {
        Workflow noSupply = new Workflow() {
            @Override
            public String id() {
                return "wf_target";
            }

            @Override
            public List<SlotSpec> slots() {
                return List.of();
            }

            @Override
            public List<WorkflowStageSpec> stages() {
                return List.of();
            }
        };
        Agent agent = agent(Set.of(AgentCapability.STREAMING), noSupply);
        ExecutionPlanner planner = new ExecutionPlanner(new RouteTable(List.of(new RouteStrategy() {
            @Override
            public int priority() {
                return 1;
            }

            @Override
            public Optional<RouteDecision> match(RouteContext context) {
                return Optional.of(RouteDecision.of(agent));
            }
        })), new InMemoryWorkflowCatalog(List.of(noSupply)), AgentWorkflowBindingResolver.DEFAULT,
                CapabilityConfig.DEFAULT, (a, w) -> PromptSnapshot.empty());

        assertThrows(AgentCapabilityContractException.class, () -> planner.plan(AgentRequest.of("问题")));
    }

    @Test
    void 换绑不同workflow_同一agent走新流程() {
        Agent agent = agent(Set.of(AgentCapability.STREAMING, AgentCapability.CITATIONS), WORKFLOW);
        Workflow other = new Workflow() {
            @Override
            public String id() {
                return "wf_other";
            }

            @Override
            public List<SlotSpec> slots() {
                return List.of();
            }

            @Override
            public List<WorkflowStageSpec> stages() {
                return List.of();
            }

            @Override
            public Set<AgentCapability> suppliedCapabilities() {
                return Set.of(AgentCapability.STREAMING, AgentCapability.CITATIONS);
            }
        };
        ExecutionPlanner planner = new ExecutionPlanner(new RouteTable(List.of(new RouteStrategy() {
            @Override
            public int priority() {
                return 1;
            }

            @Override
            public Optional<RouteDecision> match(RouteContext context) {
                return Optional.of(RouteDecision.of(agent));
            }
        })), new InMemoryWorkflowCatalog(List.of(WORKFLOW, other)),
                a -> "wf_other", CapabilityConfig.DEFAULT, (a, w) -> PromptSnapshot.empty());

        assertEquals("wf_other", planner.plan(AgentRequest.of("问题")).workflow().id());
    }
}
