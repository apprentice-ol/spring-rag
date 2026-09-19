package com.agentframework.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentframework.crosscutting.guard.Guard;
import com.agentframework.crosscutting.guard.GuardContext;
import com.agentframework.crosscutting.guard.GuardDecision;
import com.agentframework.crosscutting.guard.GuardPhase;
import com.agentframework.crosscutting.guard.Guards;
import com.agentframework.crosscutting.interceptor.Interceptor;
import com.agentframework.crosscutting.interceptor.InterceptorContext;
import com.agentframework.crosscutting.interceptor.Invocation;
import com.agentframework.definition.DefinitionValidationException;
import com.agentframework.definition.agent.AgentDefinition;
import com.agentframework.definition.node.LlmNodeDefinition;
import com.agentframework.definition.node.NodeMeta;
import com.agentframework.definition.node.ParallelNodeDefinition;
import com.agentframework.definition.node.ToolNodeDefinition;
import com.agentframework.definition.policy.AgentPolicies;
import com.agentframework.definition.policy.GuardPolicy;
import com.agentframework.definition.policy.InterceptorPolicy;
import com.agentframework.definition.policy.MergeMode;
import com.agentframework.definition.prompt.PromptDefinition;
import com.agentframework.definition.region.Paradigm;
import com.agentframework.definition.region.RegionDefinition;
import com.agentframework.definition.region.RegionPolicy;
import com.agentframework.definition.workflow.SlotType;
import com.agentframework.definition.workflow.WorkflowBuilder;
import com.agentframework.definition.workflow.WorkflowDefinition;
import com.agentframework.engine.core.Engine;
import com.agentframework.engine.core.EngineBuilder;
import com.agentframework.engine.core.RunResult;
import com.agentframework.engine.policy.Activation;
import com.agentframework.engine.policy.PolicyCatalog;
import com.agentframework.engine.policy.PolicyDirective;
import com.agentframework.engine.policy.PolicyKind;
import com.agentframework.engine.policy.PolicyRef;
import com.agentframework.engine.policy.PolicyResolver;
import com.agentframework.engine.policy.PolicyScope;
import com.agentframework.engine.policy.PolicyScopeChain;
import com.agentframework.engine.policy.PolicyScopeKind;
import com.agentframework.engine.policy.ResolvedPolicy;
import com.agentframework.infra.modelgateway.EchoModelProvider;
import com.agentframework.infra.storage.InMemoryEventBus;
import com.agentframework.engine.toolexecutor.ToolResult;
import com.agentframework.sdk.Tools;
import com.agentframework.runtime.event.Topics;
import com.agentframework.runtime.session.Input;
import com.agentframework.runtime.session.SessionState;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 策略作用域与解析链测试：继承、覆盖、强制组件、失败语义与声明期校验。 */
class PolicyResolutionTest {

    @Test
    @DisplayName("Workflow 级显式引用让 opt-in 守卫真正生效")
    void workflowScopeActivatesOptInGuard() {
        RecordingGuard sentinel = new RecordingGuard("sentinel");
        WorkflowDefinition workflow = simpleWorkflow("guard-wf", "sentinel");
        Engine engine = baseBuilder(workflow, agent("guard-agent", "guard-wf"))
                .component("sentinel", Activation.DEFAULT_OFF, sentinel)
                .build();

        RunResult result = engine.run("guard-agent", Input.of("你好"));

        assertEquals(SessionState.COMPLETED, result.state());
        assertTrue(sentinel.phases.contains(GuardPhase.BEFORE_NODE), () -> "实际阶段：" + sentinel.phases);
        assertTrue(sentinel.phases.contains(GuardPhase.AFTER_NODE), () -> "实际阶段：" + sentinel.phases);
        assertTrue(sentinel.phases.contains(GuardPhase.BEFORE_AGENT), () -> "实际阶段：" + sentinel.phases);
        assertTrue(sentinel.nodes.contains("hello"), () -> "实际节点：" + sentinel.nodes);
    }

    @Test
    @DisplayName("节点级引用把守卫限定在单个节点")
    void nodeScopeRestrictsGuardToSingleNode() {
        RecordingGuard pinned = new RecordingGuard("pinned");
        WorkflowDefinition workflow = WorkflowBuilder.create("pinned-wf", "1.0.0")
                .node(LlmNodeDefinition.of("a", "prompt-a", "out_a")
                        .withMeta(NodeMeta.empty().withGuards("pinned")))
                .node(LlmNodeDefinition.of("b", "prompt-b", "out_b")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .edge("a", "b")
                .slot("out_a", SlotType.STRING)
                .slot("out_b", SlotType.STRING)
                .build();
        Engine engine = EngineBuilder.create()
                .workflow(workflow)
                .prompt(PromptDefinition.template("prompt-a", "A"))
                .prompt(PromptDefinition.template("prompt-b", "B"))
                .modelProvider(new EchoModelProvider("echo", request -> "ok"))
                .defaultModelProvider("echo")
                .agent(agent("pinned-agent", "pinned-wf"))
                .component("pinned", Activation.DEFAULT_OFF, pinned)
                .build();

        RunResult result = engine.run("pinned-agent", Input.of("你好"));

        assertEquals(SessionState.COMPLETED, result.state());
        assertTrue(pinned.nodes.contains("a"), () -> "实际节点：" + pinned.nodes);
        assertFalse(pinned.nodes.contains("b"), () -> "节点 b 不应触发 pinned：" + pinned.nodes);
    }

    @Test
    @DisplayName("强制组件不能被禁用，通配符禁用只保留强制组件")
    void mandatorySurvivesWildcardDisable() {
        PolicyCatalog catalog = new PolicyCatalog();
        catalog.registerGuard("allow-all", Activation.DEFAULT_ON, new Guards.AllowAll());
        catalog.registerGuard("audit", Activation.MANDATORY, new RecordingGuard("audit"));
        PolicyResolver resolver = new PolicyResolver(catalog);
        PolicyScope node = PolicyScope.of(PolicyScopeKind.NODE, "n",
                PolicyDirective.of(PolicyKind.GUARD, List.of(), List.of("*"), MergeMode.ADD));

        ResolvedPolicy resolved = resolver.resolve(PolicyScopeChain.of(PolicyScope.global(), node));

        assertEquals(List.of("audit"), resolved.guards().stream().map(Guard::name).toList());
    }

    @Test
    @DisplayName("参数化组件由最靠近执行单元的作用域参数生效")
    void nearestScopeParametersWin() {
        PolicyCatalog catalog = new PolicyCatalog();
        catalog.registerFactory(PolicyKind.GUARD, "budget", Activation.DEFAULT_OFF,
                params -> new ParameterizedGuard("budget", String.valueOf(params.get("max"))));
        PolicyResolver resolver = new PolicyResolver(catalog);
        PolicyScope workflow = PolicyScope.of(PolicyScopeKind.WORKFLOW, "wf",
                PolicyDirective.enabling(PolicyKind.GUARD, List.of(PolicyRef.of("budget", Map.of("max", 3)))));
        PolicyScope node = PolicyScope.of(PolicyScopeKind.NODE, "n",
                PolicyDirective.enabling(PolicyKind.GUARD, List.of(PolicyRef.of("budget", Map.of("max", 5)))));

        ResolvedPolicy resolved = resolver.resolve(PolicyScopeChain.of(PolicyScope.global(), workflow, node));

        ParameterizedGuard budget = (ParameterizedGuard) resolved.guards().stream()
                .filter(guard -> guard.name().equals("budget"))
                .findFirst()
                .orElseThrow();
        assertEquals("5", budget.value);
    }

    @Test
    @DisplayName("拦截器三态开关：关闭缓存后其余拦截器保持生效")
    void interceptorTriStateDisablesCacheOnly() {
        PolicyCatalog catalog = new PolicyCatalog();
        catalog.registerInterceptor("trace", Activation.DEFAULT_ON, new NamedInterceptor("trace"));
        catalog.registerInterceptor("cache", Activation.DEFAULT_ON, new NamedInterceptor("cache"));
        catalog.registerInterceptor("metrics", Activation.DEFAULT_ON, new NamedInterceptor("metrics"));
        PolicyResolver resolver = new PolicyResolver(catalog);
        AgentDefinition definition = AgentDefinition.builder("tri-agent").workflow("tri-wf")
                .policies(new AgentPolicies(null, null, null, InterceptorPolicy.of().withoutCache(), null))
                .build();

        ResolvedPolicy resolved = resolver.resolve(PolicyScopeChain.of(PolicyScope.global(),
                PolicyScope.agent(definition), PolicyScope.workflow(triWorkflow())));

        List<String> names = resolved.interceptors().stream().map(Interceptor::name).toList();
        assertTrue(names.contains("trace"), () -> "实际拦截器：" + names);
        assertTrue(names.contains("metrics"), () -> "实际拦截器：" + names);
        assertFalse(names.contains("cache"), () -> "实际拦截器：" + names);
    }

    @Test
    @DisplayName("守卫默认失败关闭，显式 ALLOW 时放行并留下审计事件")
    void guardFailureModeIsHonored() {
        Guard boom = new Guard() {
            @Override
            public String name() {
                return "boom";
            }

            @Override
            public GuardDecision check(GuardContext context) {
                throw new IllegalStateException("炸了");
            }
        };
        Engine denyEngine = baseBuilder(simpleWorkflow("fail-wf", "boom"),
                withGuardPolicy("deny-agent", "fail-wf", GuardPolicy.FailureMode.DENY))
                .component("boom", Activation.DEFAULT_OFF, boom)
                .build();

        RunResult denied = denyEngine.run("deny-agent", Input.of("你好"));

        assertEquals(SessionState.FAILED, denied.state());
        assertTrue(denied.error().contains("失败关闭"), () -> "实际错误：" + denied.error());

        InMemoryEventBus events = new InMemoryEventBus();
        Engine allowEngine = baseBuilder(simpleWorkflow("fail-wf", "boom"),
                withGuardPolicy("allow-agent", "fail-wf", GuardPolicy.FailureMode.ALLOW))
                .component("boom", Activation.DEFAULT_OFF, boom)
                .events(events)
                .build();

        RunResult allowed = allowEngine.run("allow-agent", Input.of("你好"));

        assertEquals(SessionState.COMPLETED, allowed.state(), () -> "实际错误：" + allowed.error());
        assertTrue(events.count(Topics.GUARD_FAILED_OPEN) >= 1L);
    }

    @Test
    @DisplayName("未注册引用在创建 Agent 时失败并给出候选")
    void unknownRefFailsWithCandidates() {
        WorkflowDefinition workflow = simpleWorkflow("bad-ref-wf", "content-gard");
        Engine engine = baseBuilder(workflow, agent("bad-ref-agent", "bad-ref-wf"))
                .guard(Guards.Content.of("机密"))
                .build();

        DefinitionValidationException failure = assertThrows(DefinitionValidationException.class,
                () -> engine.run("bad-ref-agent", Input.of("你好")));

        assertTrue(failure.getMessage().contains("POLICY_REF_UNKNOWN"), () -> "实际消息：" + failure.getMessage());
        assertTrue(failure.getMessage().contains("content-guard"), () -> "实际消息：" + failure.getMessage());
    }

    @Test
    @DisplayName("默认生效的组件按 order 排序，未声明策略时与旧行为一致")
    void defaultOnComponentsAreSorted() {
        PolicyCatalog catalog = new PolicyCatalog();
        catalog.registerGuard("late", Activation.DEFAULT_ON, orderedGuard("late", 90));
        catalog.registerGuard("early", Activation.DEFAULT_ON, orderedGuard("early", 10));
        PolicyResolver resolver = new PolicyResolver(catalog);

        ResolvedPolicy resolved = resolver.resolve(PolicyScopeChain.of(PolicyScope.global()));

        assertEquals(List.of("early", "late"), resolved.guards().stream().map(Guard::name).toList());
        assertFalse(resolved.isEmpty());
    }

    @Test
    @DisplayName("LLM 挂载点已接线：BEFORE_LLM / AFTER_LLM 守卫会执行")
    void llmPhasesAreWired() {
        RecordingGuard llmGuard = new RecordingGuard("llm-guard");
        Engine engine = baseBuilder(simpleWorkflow("llm-wf", "llm-guard"), agent("llm-agent", "llm-wf"))
                .component("llm-guard", Activation.DEFAULT_OFF, llmGuard)
                .build();

        RunResult result = engine.run("llm-agent", Input.of("你好"));

        assertEquals(SessionState.COMPLETED, result.state());
        assertTrue(llmGuard.phases.contains(GuardPhase.BEFORE_LLM), () -> "实际阶段：" + llmGuard.phases);
        assertTrue(llmGuard.phases.contains(GuardPhase.AFTER_LLM), () -> "实际阶段：" + llmGuard.phases);
    }

    @Test
    @DisplayName("工具挂载点已接线：BEFORE_TOOL / AFTER_TOOL 守卫会执行")
    void toolPhasesAreWired() {
        RecordingGuard toolGuard = new RecordingGuard("tool-guard");
        WorkflowDefinition workflow = WorkflowBuilder.create("tool-wf", "1.0.0")
                .node(ToolNodeDefinition.of("call", "adder", "sum")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .slot("sum", SlotType.STRING)
                .guards("tool-guard")
                .build();
        Engine engine = EngineBuilder.create()
                .workflow(workflow)
                .tool(Tools.of("adder", "加法", input -> ToolResult.ok("ok")))
                .agent(agent("tool-agent", "tool-wf"))
                .component("tool-guard", Activation.DEFAULT_OFF, toolGuard)
                .build();

        RunResult result = engine.run("tool-agent", Input.of("算一下"));

        assertEquals(SessionState.COMPLETED, result.state(), () -> "实际错误：" + result.error());
        assertTrue(toolGuard.phases.contains(GuardPhase.BEFORE_TOOL), () -> "实际阶段：" + toolGuard.phases);
        assertTrue(toolGuard.phases.contains(GuardPhase.AFTER_TOOL), () -> "实际阶段：" + toolGuard.phases);
    }

    @Test
    @DisplayName("并行分支按各自节点解析策略，互不串扰")
    void parallelBranchesResolveTheirOwnPolicy() {
        RecordingGuard branchGuard = new RecordingGuard("branch-guard");
        WorkflowDefinition workflow = WorkflowBuilder.create("par-wf", "1.0.0")
                .node(ParallelNodeDefinition.of("fan-out", "branches", "branch-a", "branch-b"))
                .node(LlmNodeDefinition.of("branch-a", "prompt-a", "out_a")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true).withGuards("branch-guard")))
                .node(LlmNodeDefinition.of("branch-b", "prompt-b", "out_b")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .slot("branches", SlotType.OBJECT)
                .build();
        Engine engine = EngineBuilder.create()
                .workflow(workflow)
                .prompt(PromptDefinition.template("prompt-a", "A"))
                .prompt(PromptDefinition.template("prompt-b", "B"))
                .modelProvider(new EchoModelProvider("echo", request -> "ok"))
                .defaultModelProvider("echo")
                .agent(agent("par-agent", "par-wf"))
                .component("branch-guard", Activation.DEFAULT_OFF, branchGuard)
                .build();

        RunResult result = engine.run("par-agent", Input.of("并行"));

        assertEquals(SessionState.COMPLETED, result.state(), () -> "实际错误：" + result.error());
        assertTrue(branchGuard.nodes.contains("branch-a"), () -> "实际节点：" + branchGuard.nodes);
        assertFalse(branchGuard.nodes.contains("branch-b"), () -> "实际节点：" + branchGuard.nodes);
    }

    @Test
    @DisplayName("Prompt 资产上的守卫引用只作用于使用该 Prompt 的节点")
    void promptGuardRefsApplyOnlyToThatNode() {
        RecordingGuard promptGuard = new RecordingGuard("prompt-guard");
        WorkflowDefinition workflow = WorkflowBuilder.create("prompt-wf", "1.0.0")
                .node(LlmNodeDefinition.of("guarded", "guarded-prompt", "out_guarded"))
                .node(LlmNodeDefinition.of("plain", "plain-prompt", "out_plain")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .edge("guarded", "plain")
                .slot("out_guarded", SlotType.STRING)
                .slot("out_plain", SlotType.STRING)
                .build();
        Engine engine = EngineBuilder.create()
                .workflow(workflow)
                .prompt(PromptDefinition.template("guarded-prompt", "A").withGuards("prompt-guard"))
                .prompt(PromptDefinition.template("plain-prompt", "B"))
                .modelProvider(new EchoModelProvider("echo", request -> "ok"))
                .defaultModelProvider("echo")
                .agent(agent("prompt-agent", "prompt-wf"))
                .component("prompt-guard", Activation.DEFAULT_OFF, promptGuard)
                .build();

        RunResult result = engine.run("prompt-agent", Input.of("你好"));

        assertEquals(SessionState.COMPLETED, result.state(), () -> "实际错误：" + result.error());
        assertTrue(promptGuard.nodes.contains("guarded"), () -> "实际节点：" + promptGuard.nodes);
        assertFalse(promptGuard.nodes.contains("plain"), () -> "实际节点：" + promptGuard.nodes);
        assertTrue(promptGuard.phases.contains(GuardPhase.BEFORE_PROMPT), () -> "实际阶段：" + promptGuard.phases);
    }

    @Test
    @DisplayName("Prompt 资产上的未知引用在创建 Agent 时报错，并定位到 prompt")
    void promptPolicyRefValidationReportsUnknown() {
        Engine engine = EngineBuilder.create()
                .workflow(simpleWorkflow("prompt-bad-wf"))
                .prompt(PromptDefinition.template("hello-prompt", "问候：{{messages}}").withGuards("missing-guard"))
                .modelProvider(new EchoModelProvider("echo", request -> "你好"))
                .defaultModelProvider("echo")
                .agent(agent("prompt-bad-agent", "prompt-bad-wf"))
                .build();

        DefinitionValidationException failure = assertThrows(DefinitionValidationException.class,
                () -> engine.run("prompt-bad-agent", Input.of("你好")));

        assertTrue(failure.getMessage().contains("POLICY_REF_UNKNOWN"), () -> "实际消息：" + failure.getMessage());
        assertTrue(failure.getMessage().contains("prompt:hello-prompt@latest"),
                () -> "实际消息：" + failure.getMessage());
    }

    @Test
    @DisplayName("声明 Region 不改变执行结果（M2.1 只做定义与校验）")
    void regionDeclarationDoesNotChangeExecution() {
        Engine plainEngine = baseBuilder(singleNodeWorkflow("exec-plain", null), agent("plain-agent", "exec-plain"))
                .build();
        Engine regionEngine = baseBuilder(singleNodeWorkflow("exec-region", "r1"),
                agent("region-agent", "exec-region"))
                .build();

        RunResult plain = plainEngine.run("plain-agent", Input.of("你好"));
        RunResult withRegion = regionEngine.run("region-agent", Input.of("你好"));

        assertEquals(SessionState.COMPLETED, withRegion.state(), () -> "实际错误：" + withRegion.error());
        assertEquals(plain.visitedNodes(), withRegion.visitedNodes());
        assertEquals(plain.output(), withRegion.output());
        assertEquals(plain.slots(), withRegion.slots());
    }

    @Test
    @DisplayName("Region 策略中的未知引用在创建 Agent 时报错，并定位到 region")
    void regionPolicyRefValidationReportsUnknown() {
        WorkflowDefinition workflow = WorkflowBuilder.create("region-bad-wf", "1.0.0")
                .node(LlmNodeDefinition.of("hello", "hello-prompt", "greeting")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .slot("greeting", SlotType.STRING)
                .region(RegionDefinition.of("r1", Paradigm.ROUTER, "hello")
                        .withPolicy(new RegionPolicy(RegionPolicy.bindings("missing-region-guard"), null, null, null)))
                .build();
        Engine engine = baseBuilder(workflow, agent("region-bad-agent", "region-bad-wf")).build();

        DefinitionValidationException failure = assertThrows(DefinitionValidationException.class,
                () -> engine.run("region-bad-agent", Input.of("你好")));

        assertTrue(failure.getMessage().contains("POLICY_REF_UNKNOWN"), () -> "实际消息：" + failure.getMessage());
        assertTrue(failure.getMessage().contains("region:r1/policy.guards"),
                () -> "实际消息：" + failure.getMessage());
    }

    /**
     * @param id     工作流 id
     * @param guards 工作流级守卫引用
     * @return 单节点工作流
     */
    private WorkflowDefinition simpleWorkflow(String id, String... guards) {
        return WorkflowBuilder.create(id, "1.0.0")
                .node(LlmNodeDefinition.of("hello", "hello-prompt", "greeting")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .slot("greeting", SlotType.STRING)
                .guards(guards)
                .build();
    }

    /**
     * @param id       工作流 id
     * @param regionId 可选的 Region id，null 表示不声明
     * @return 单节点工作流
     */
    private WorkflowDefinition singleNodeWorkflow(String id, String regionId) {
        WorkflowBuilder builder = WorkflowBuilder.create(id, "1.0.0")
                .node(LlmNodeDefinition.of("hello", "hello-prompt", "greeting")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .slot("greeting", SlotType.STRING);
        if (regionId != null) {
            builder.region(RegionDefinition.of(regionId, Paradigm.ROUTER, "hello"));
        }
        return builder.build();
    }

    /**
     * @param id        Agent id
     * @param workflowId 工作流 id
     * @return 默认策略的 Agent 定义
     */
    private AgentDefinition agent(String id, String workflowId) {
        return AgentDefinition.builder(id).workflow(workflowId).model("echo", "echo").build();
    }

    /**
     * @param id       Agent id
     * @param workflowId 工作流 id
     * @param mode     守卫失败语义
     * @return 带守卫策略的 Agent 定义
     */
    private AgentDefinition withGuardPolicy(String id, String workflowId, GuardPolicy.FailureMode mode) {
        return AgentDefinition.builder(id).workflow(workflowId).model("echo", "echo")
                .policies(new AgentPolicies(null, GuardPolicy.of("boom").withFailureMode(mode), null, null, null))
                .build();
    }

    /**
     * @param workflow 工作流
     * @param agent    Agent 定义
     * @return 基础引擎构建器
     */
    private EngineBuilder baseBuilder(WorkflowDefinition workflow, AgentDefinition agent) {
        return EngineBuilder.create()
                .workflow(workflow)
                .prompt(PromptDefinition.template("hello-prompt", "问候：{{messages}}"))
                .modelProvider(new EchoModelProvider("echo", request -> "你好"))
                .defaultModelProvider("echo")
                .agent(agent);
    }

    /** @return 三态测试用工作流 */
    private WorkflowDefinition triWorkflow() {
        return WorkflowBuilder.create("tri-wf", "1.0.0")
                .node(LlmNodeDefinition.of("hello", "hello-prompt", "greeting")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .slot("greeting", SlotType.STRING)
                .build();
    }

    /**
     * @param name  名称
     * @param order 顺序
     * @return 固定顺序的守卫
     */
    private Guard orderedGuard(String name, int order) {
        return new Guard() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public int order() {
                return order;
            }

            @Override
            public GuardDecision check(GuardContext context) {
                return GuardDecision.allow();
            }
        };
    }

    /** 记录调用阶段与节点的守卫。 */
    private static final class RecordingGuard implements Guard {

        private final String name;
        private final List<GuardPhase> phases = new CopyOnWriteArrayList<>();
        private final List<String> nodes = new CopyOnWriteArrayList<>();

        RecordingGuard(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public int order() {
            return 50;
        }

        @Override
        public GuardDecision check(GuardContext context) {
            phases.add(context.phase());
            if (context.nodeId() != null) {
                nodes.add(context.nodeId());
            }
            return GuardDecision.allow();
        }
    }

    /** 捕获参数的守卫。 */
    private static final class ParameterizedGuard implements Guard {

        private final String name;
        private final String value;

        ParameterizedGuard(String name, String value) {
            this.name = name;
            this.value = value;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public GuardDecision check(GuardContext context) {
            return GuardDecision.allow();
        }
    }

    /** 直通拦截器。 */
    private static final class NamedInterceptor implements Interceptor {

        private final String name;

        NamedInterceptor(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public <T> T intercept(Invocation<T> invocation, InterceptorContext context) throws Exception {
            return invocation.proceed();
        }
    }

}
