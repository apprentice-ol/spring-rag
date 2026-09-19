package com.agentframework.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentframework.definition.agent.AgentDefinition;
import com.agentframework.definition.agent.ModelConfig;
import com.agentframework.definition.codec.DefinitionKind;
import com.agentframework.definition.codec.DefinitionLoader;
import com.agentframework.definition.codec.JsonDefinitionCodec;
import com.agentframework.definition.codec.JsonSupport;
import com.agentframework.definition.codec.LoadResult;
import com.agentframework.definition.node.ConditionNodeDefinition;
import com.agentframework.definition.node.CustomNodeDefinition;
import com.agentframework.definition.node.HumanNodeDefinition;
import com.agentframework.definition.node.LlmNodeDefinition;
import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.node.NodeMeta;
import com.agentframework.definition.node.ParallelNodeDefinition;
import com.agentframework.definition.node.SubWorkflowNodeDefinition;
import com.agentframework.definition.node.ToolNodeDefinition;
import com.agentframework.definition.policy.AgentPolicies;
import com.agentframework.definition.policy.CachePolicy;
import com.agentframework.definition.policy.FilterPolicy;
import com.agentframework.definition.policy.GuardPolicy;
import com.agentframework.definition.policy.InterceptorPolicy;
import com.agentframework.definition.policy.QuotaPolicy;
import com.agentframework.definition.policy.RetryPolicy;
import com.agentframework.definition.policy.TimeoutPolicy;
import com.agentframework.definition.policy.ToolPolicy;
import com.agentframework.definition.prompt.PromptDefinition;
import com.agentframework.definition.region.LoopConvergence;
import com.agentframework.definition.region.Paradigm;
import com.agentframework.definition.region.RegionDefinition;
import com.agentframework.definition.region.RegionLoop;
import com.agentframework.definition.region.RegionPolicy;
import com.agentframework.definition.tool.ToolDefinition;
import com.agentframework.definition.tool.ToolParameter;
import com.agentframework.definition.tool.ToolPermission;
import com.agentframework.definition.tool.ToolSchema;
import com.agentframework.definition.workflow.PromptPolicy;
import com.agentframework.definition.workflow.SlotSpec;
import com.agentframework.definition.workflow.SlotType;
import com.agentframework.definition.workflow.WorkflowBuilder;
import com.agentframework.definition.workflow.WorkflowDefinition;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 声明式定义测试：JSON 编解码 round-trip、加载即校验、schema 与 kind 检查。 */
class DefinitionCodecTest {

    private final DefinitionLoader loader = new DefinitionLoader();

    @Test
    @DisplayName("工作流定义可无损 round-trip（含全部节点类型、区域、循环、动态边）")
    void workflowRoundTrip() {
        WorkflowDefinition workflow = richWorkflow();

        WorkflowDefinition decoded = roundTrip(DefinitionKind.WORKFLOW, workflow);

        assertEquals(workflow, decoded);
        assertEquals(10, decoded.nodes().size());
        assertInstanceOf(LlmNodeDefinition.class, decoded.requireNode("classify"));
        assertInstanceOf(ConditionNodeDefinition.class, decoded.requireNode("decide"));
        assertInstanceOf(ParallelNodeDefinition.class, decoded.requireNode("fanout"));
        assertInstanceOf(HumanNodeDefinition.class, decoded.requireNode("approve"));
        assertInstanceOf(SubWorkflowNodeDefinition.class, decoded.requireNode("sub"));
        assertInstanceOf(CustomNodeDefinition.class, decoded.requireNode("custom"));
        assertEquals(3, decoded.region("r1").orElseThrow().loop().maxIterations());
        assertEquals(0.5, decoded.region("r1").orElseThrow().loop().convergence().minDelta());
        assertTrue(decoded.dynamicPolicy().allows("classify", "search"));
    }

    @Test
    @DisplayName("Agent / Prompt / Tool 定义可无损 round-trip")
    void agentPromptToolRoundTrip() {
        AgentDefinition agent = AgentDefinition.builder("ops-agent", "1.0.0")
                .workflow("ops", "1.0.0")
                .promptProfile("ops-profile")
                .model(ModelConfig.of("echo", "m")
                        .withTemperature(0.2)
                        .withMaxTokens(100)
                        .withTimeout(Duration.ofSeconds(5))
                        .withRetry(RetryPolicy.of(2, Duration.ofMillis(10))))
                .policies(new AgentPolicies(
                        ToolPolicy.only("web-search").withApprovalRequired("deploy"),
                        GuardPolicy.of("content-guard"),
                        FilterPolicy.of("pii"),
                        InterceptorPolicy.of("trace"),
                        QuotaPolicy.of(1000, 10, 5)))
                .workspaceTemplate("default")
                .metadata("team", "ops")
                .build();
        PromptDefinition prompt = PromptDefinition.template("prompt-classify", "分类：{{slots.x}}")
                .withVariable("x", "string", true)
                .withGuards("content-guard")
                .withFilters("pii")
                .withRenderer("template")
                .withMetadata("owner", "ops");
        ToolDefinition tool = ToolDefinition.of("web-search", "1.0.0",
                        ToolSchema.of("web-search", "搜索",
                                ToolParameter.required("query", "string").describedAs("查询"),
                                ToolParameter.of("limit", "number", 5)))
                .withExecutorRef("web-search-exec")
                .withPermission(ToolPermission.readOnly())
                .withCache(CachePolicy.content(Duration.ofMinutes(1)))
                .withTimeout(TimeoutPolicy.of(Duration.ofSeconds(3)))
                .withRetry(RetryPolicy.of(2, Duration.ofMillis(5)));

        assertEquals(agent, roundTrip(DefinitionKind.AGENT, agent));
        assertEquals(prompt, roundTrip(DefinitionKind.PROMPT, prompt));
        assertEquals(tool, roundTrip(DefinitionKind.TOOL, tool));
    }

    @Test
    @DisplayName("加载即校验：结构错误随报告返回且不被接受")
    void workflowValidationRunsOnLoad() {
        Map<String, Object> document = loader.codec()
                .encode(DefinitionKind.WORKFLOW, richWorkflow());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) document.get("edges");
        edges.add(Map.of("from", "classify", "to", "missing-node", "priority", 0));

        LoadResult result = loader.load(DefinitionKind.WORKFLOW, document);

        assertFalse(result.accepted());
        assertTrue(result.report().errors().stream()
                .anyMatch(problem -> problem.code().equals("GRAPH_DANGLING_EDGE")));
    }

    @Test
    @DisplayName("schemaVersion 与 kind 在解码前校验")
    void schemaAndKindAreChecked() {
        Map<String, Object> document = loader.codec().encode(DefinitionKind.WORKFLOW, richWorkflow());

        Map<String, Object> wrongSchema = new java.util.LinkedHashMap<>(document);
        wrongSchema.put("schemaVersion", 99);
        LoadResult schemaFailure = loader.load(DefinitionKind.WORKFLOW, wrongSchema);
        assertTrue(schemaFailure.report().errors().stream()
                .anyMatch(problem -> problem.code().equals("DEFINITION_SCHEMA_UNSUPPORTED")));

        LoadResult kindFailure = loader.load(DefinitionKind.AGENT, document);
        assertTrue(kindFailure.report().errors().stream()
                .anyMatch(problem -> problem.code().equals("DEFINITION_KIND_MISMATCH")));
    }

    @Test
    @DisplayName("未识别字段只提示，不影响接受")
    void unknownFieldIsWarning() {
        Map<String, Object> document = new java.util.LinkedHashMap<>(
                loader.codec().encode(DefinitionKind.WORKFLOW, richWorkflow()));
        document.put("futureField", "x");

        LoadResult result = loader.load(DefinitionKind.WORKFLOW, document);

        assertTrue(result.accepted(), () -> "报告：" + result.report().messages());
        assertTrue(result.report().problems().stream()
                .anyMatch(problem -> problem.code().equals("DEFINITION_FIELD_UNKNOWN")));
    }

    @Test
    @DisplayName("解析失败与字段缺失都有明确错误码")
    void parseAndFieldErrors() {
        LoadResult parseFailure = loader.load(DefinitionKind.WORKFLOW, "{ this is not json");
        assertTrue(parseFailure.report().errors().stream()
                .anyMatch(problem -> problem.code().equals("DEFINITION_PARSE_ERROR")));

        LoadResult fieldFailure = loader.load(DefinitionKind.WORKFLOW,
                Map.of("schemaVersion", 1, "kind", "workflow", "id", "broken"));
        assertFalse(fieldFailure.accepted());
        assertTrue(fieldFailure.report().hasErrors());
    }

    @Test
    @DisplayName("JSON 文本可写可读，转义正确")
    void jsonTextRoundTrip() {
        String text = JsonSupport.write(Map.of("name", "换行\n引号\"反斜杠\\", "count", 3, "flag", true));

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = (Map<String, Object>) JsonSupport.parse(text);

        assertEquals("换行\n引号\"反斜杠\\", parsed.get("name"));
        assertEquals(3L, parsed.get("count"));
        assertEquals(Boolean.TRUE, parsed.get("flag"));
    }

    /**
     * @param kind       定义种类
     * @param definition 定义对象
     * @param <T>        类型参数
     * @return 经 JSON 文本往返后的定义
     */
    @SuppressWarnings("unchecked")
    private <T> T roundTrip(DefinitionKind kind, T definition) {
        Map<String, Object> document = loader.codec().encode(kind, definition);
        String json = JsonSupport.write(document);
        LoadResult result = loader.load(kind, json);
        assertTrue(result.accepted(), () -> "加载失败：" + result.report().messages());
        return (T) result.definition();
    }

    /**
     * @return 覆盖全部节点类型、区域循环与动态边的工作流
     */
    private WorkflowDefinition richWorkflow() {
        return WorkflowBuilder.create("ops", "1.0.0")
                .node(LlmNodeDefinition.of("classify", "prompt-classify", "intent")
                        .withPromptVersion("1.0.0")
                        .withMeta(NodeMeta.empty().withGuards("content-guard").withFilters("pii")))
                .node(ToolNodeDefinition.of("search", "web-search", "hits")
                        .withArgument("query", "${slots.intent}"))
                .node(ConditionNodeDefinition.of("decide",
                        new ConditionNodeDefinition.Branch("reflect", "slots.hits contains 'x'"),
                        ConditionNodeDefinition.Branch.otherwise("done")))
                .node(ParallelNodeDefinition.of("fanout", "branches", "search", "classify")
                        .withJoinStrategy(ParallelNodeDefinition.JoinStrategy.ANY)
                        .withMaxConcurrency(2))
                .node(new HumanNodeDefinition("approve", "确认 {{intent}}", "approval", "approval",
                        List.of("yes", "no"), Duration.ofMinutes(5), NodeMeta.empty()))
                .node(SubWorkflowNodeDefinition.of("sub", "child", "child_result")
                        .withInput("q", "slots.intent"))
                .node(CustomNodeDefinition.of("custom", "upper-case", "custom_out"))
                .node(LlmNodeDefinition.of("reflect", "prompt-reflect", "critique"))
                .node(LlmNodeDefinition.of("critique", "prompt-critique", "score"))
                .node(LlmNodeDefinition.of("done", "prompt-done", "report")
                        .withMeta(NodeMeta.empty().withAttribute("terminal", true)))
                .edge("classify", "search")
                .edge("classify", "decide")
                .edge("search", "reflect")
                .edge("decide", "reflect")
                .edge("decide", "done")
                .edge("reflect", "critique")
                .edgePriority("critique", "reflect", 10)
                .edge("critique", "done")
                .slot(SlotSpec.required("intent", SlotType.STRING))
                .slot("hits", SlotType.STRING)
                .slot("critique", SlotType.STRING)
                .slot("score", SlotType.NUMBER)
                .slot("iteration", SlotType.NUMBER)
                .slot("branches", SlotType.OBJECT)
                .slot("approval", SlotType.STRING)
                .slot("child_result", SlotType.STRING)
                .slot("custom_out", SlotType.STRING)
                .slot("report", SlotType.STRING)
                .requireTool("web-search")
                .promptPolicy(PromptPolicy.of("ops-profile"))
                .guards("content-guard")
                .filters("pii")
                .interceptors("trace")
                .cache(CachePolicy.explicit(Duration.ofMinutes(5), "intent"))
                .tracePoint("checkpoint")
                .region(RegionDefinition.of("r1", Paradigm.REFLECTION, "reflect", "critique")
                        .withLoop(RegionLoop.of("reflect", "critique", 3)
                                .withCounterSlot("iteration")
                                .withConvergence(LoopConvergence.of("score", 0.5)))
                        .withPolicy(new RegionPolicy(RegionPolicy.bindings("content-guard"), null, null,
                                QuotaPolicy.of(1000, 10, 5))))
                .dynamic("classify", "search", "decide")
                .metadata("owner", "ops-team")
                .build();
    }
}
