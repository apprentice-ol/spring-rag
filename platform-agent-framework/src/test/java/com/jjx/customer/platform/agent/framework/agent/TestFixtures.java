package com.jjx.customer.platform.agent.framework.agent;

import com.jjx.customer.platform.agent.framework.agent.Agent;
import com.jjx.customer.platform.agent.framework.agent.AgentRequest;
import com.jjx.customer.platform.agent.framework.capability.AgentCapability;
import com.jjx.customer.platform.agent.framework.prompt.PromptSnapshot;
import com.jjx.customer.platform.agent.framework.tool.AgentTool;
import com.jjx.customer.platform.agent.framework.tool.BaseTool;
import com.jjx.customer.platform.agent.framework.tool.ExtensionTool;
import com.jjx.customer.platform.agent.framework.tool.ToolInvocation;
import com.jjx.customer.platform.agent.framework.tool.ToolResult;
import com.jjx.customer.platform.agent.framework.workflow.SlotSpec;
import com.jjx.customer.platform.agent.framework.workflow.WorkflowDriver;
import com.jjx.customer.platform.agent.framework.workflow.WorkflowStageSpec;
import com.jjx.customer.platform.agent.framework.workflow.Workflow;
import com.jjx.customer.platform.agent.framework.workflow.WorkflowCatalog;
import com.jjx.customer.platform.agent.framework.agent.AgentWorkflowBindingResolver;
import com.jjx.customer.platform.agent.framework.capability.CapabilityConfig;
import com.jjx.customer.platform.agent.framework.model.ModelPort;
import com.jjx.customer.platform.agent.framework.node.AgentCallNodeExecutor;
import com.jjx.customer.platform.agent.framework.node.DeterministicNodeExecutor;
import com.jjx.customer.platform.agent.framework.node.LoopNodeExecutor;
import com.jjx.customer.platform.agent.framework.node.NodeExecutor;
import com.jjx.customer.platform.agent.framework.node.NodeExecutorRegistry;
import com.jjx.customer.platform.agent.framework.agent.AgentRegistry;
import com.jjx.customer.platform.agent.framework.workflow.InMemoryWorkflowCatalog;
import com.jjx.customer.platform.agent.framework.route.RouteContext;
import com.jjx.customer.platform.agent.framework.route.RouteDecision;
import com.jjx.customer.platform.agent.framework.route.RouteStrategy;
import com.jjx.customer.platform.agent.framework.route.RouteTable;
import com.jjx.customer.platform.agent.framework.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 测试夹具：内存实现 + 端到端引擎装配。 */
public final class TestFixtures {

    private TestFixtures() {
    }

    public static ExtensionTool ext(String name, ToolResult result) {
        return new ExtensionTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "测试扩展工具 " + name;
            }

            @Override
            public String inputSchema() {
                return "{\"type\":\"object\",\"properties\":{\"input\":{\"type\":\"string\"}}}";
            }

            @Override
            public ToolResult execute(ToolInvocation invocation) {
                return result;
            }
        };
    }

    public static ExtensionTool throwingExt(String name) {
        return new ExtensionTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "总是失败的测试工具";
            }

            @Override
            public String inputSchema() {
                return "{\"type\":\"object\"}";
            }

            @Override
            public ToolResult execute(ToolInvocation invocation) {
                throw new IllegalStateException("工具炸了");
            }
        };
    }

    public static BaseTool base(String name) {
        return new BaseTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "循环保障工具 " + name;
            }

            @Override
            public String inputSchema() {
                return "{\"type\":\"object\"}";
            }

            @Override
            public ToolResult execute(ToolInvocation invocation) {
                return ToolResult.ok("base:" + name);
            }
        };
    }

    public static Agent agent(String id, String domain, Set<AgentCapability> capabilities, Workflow workflow) {
        return new Agent() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String intentDomain() {
                return domain;
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

    public static Workflow workflow(String id, List<SlotSpec> slots, List<WorkflowStageSpec> stages,
                                    Set<AgentCapability> supplied, String answerPromptKey) {
        return workflow(id, slots, stages, supplied, answerPromptKey, 0);
    }

    public static Workflow workflow(String id, List<SlotSpec> slots, List<WorkflowStageSpec> stages,
                                    Set<AgentCapability> supplied, String answerPromptKey, int maxLlmCalls) {
        return new Workflow() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public List<SlotSpec> slots() {
                return slots;
            }

            @Override
            public List<WorkflowStageSpec> stages() {
                return stages;
            }

            @Override
            public Set<AgentCapability> suppliedCapabilities() {
                return supplied;
            }

            @Override
            public String answerPromptKey() {
                return answerPromptKey;
            }

            @Override
            public int maxLlmCalls() {
                return maxLlmCalls;
            }
        };
    }

    /** 端到端引擎：路由按请求属性 domain 选 Agent（缺省取第一个）。 */
    public static WorkflowEngine engine(List<Agent> agents, List<AgentTool> tools,
                                        ModelPort modelPort, Map<String, String> prompts) {
        ToolRegistry toolRegistry = new ToolRegistry(tools);
        List<NodeExecutor> executors = new ArrayList<>(List.of(
                new DeterministicNodeExecutor(toolRegistry),
                new LoopNodeExecutor(modelPort, toolRegistry),
                new AgentCallNodeExecutor()));
        return engine(agents, toolRegistry, executors, Agents.of(agents), prompts);
    }

    /** 引擎装配（可覆写节点执行器）。 */
    public static WorkflowEngine engine(List<Agent> agents, ToolRegistry toolRegistry,
                                        List<NodeExecutor> executors, AgentRegistry agentRegistry,
                                        Map<String, String> prompts) {
        List<Workflow> workflows = agents.stream().map(Agent::workflow).distinct().toList();
        WorkflowCatalog catalog = new InMemoryWorkflowCatalog(workflows);
        RouteStrategy strategy = new RouteStrategy() {
            @Override
            public int priority() {
                return 10;
            }

            @Override
            public Optional<RouteDecision> match(RouteContext context) {
                String domain = String.valueOf(context.request().attributes().getOrDefault("domain", ""));
                return agentRegistry.all().stream()
                        .filter(a -> a.intentDomain().equals(domain))
                        .findFirst()
                        .or(() -> agentRegistry.all().stream().findFirst())
                        .map(RouteDecision::of);
            }
        };
        ExecutionPlanner planner = new ExecutionPlanner(new RouteTable(List.of(strategy)), catalog,
                AgentWorkflowBindingResolver.DEFAULT, CapabilityConfig.DEFAULT,
                (agent, workflow) -> new PromptSnapshot(prompts, "test@r1", "hash-1", "1:1"));
        WorkflowDriver driver = new com.jjx.customer.platform.agent.framework.workflow.DefaultWorkflowDriver(
                new NodeExecutorRegistry(executors));
        return new WorkflowEngine(planner, driver, agentRegistry, 2);
    }

    /** 便捷：单 Agent 引擎。 */
    public static WorkflowEngine single(Agent agent, List<AgentTool> tools, ModelPort modelPort,
                                        Map<String, String> prompts) {
        return engine(List.of(agent), tools, modelPort, prompts);
    }

    public static AgentRequest request(String input) {
        return new AgentRequest(input, Map.of());
    }

    /** 记录元数据流出顺序的 sink。 */
    public static final class RecordingSink
            implements com.jjx.customer.platform.agent.framework.result.ExecutionMetadataSink {

        public final List<String> events = new ArrayList<>();

        @Override
        public void onContextReady(com.jjx.customer.platform.agent.framework.result.ContextBundle context) {
            events.add("context");
        }

        @Override
        public void onCitationsReady(com.jjx.customer.platform.agent.framework.result.CitationIndex index) {
            events.add("citations");
        }

        @Override
        public void onFingerprint(com.jjx.customer.platform.agent.framework.result.ExecutionFingerprint fingerprint) {
            events.add("fingerprint");
        }

        @Override
        public void onRetrievalStats(com.jjx.customer.platform.agent.framework.result.RetrievalStats stats) {
            events.add("stats");
        }

        @Override
        public void onTraceUpdate(com.jjx.customer.platform.agent.framework.trace.AgentTrace trace) {
            events.add("trace");
        }
    }

    /** 小工具：把 agents 包成注册表。 */
    private static final class Agents {
        private Agents() {
        }

        static AgentRegistry of(List<Agent> agents) {
            return new AgentRegistry(agents);
        }
    }
}
