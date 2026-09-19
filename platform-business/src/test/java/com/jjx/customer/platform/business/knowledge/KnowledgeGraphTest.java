package com.jjx.customer.platform.business.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agentframework.definition.agent.AgentDefinition;
import com.agentframework.definition.prompt.PromptDefinition;
import com.agentframework.definition.tool.ToolParameter;
import com.agentframework.definition.tool.ToolSchema;
import com.agentframework.engine.core.Engine;
import com.agentframework.engine.core.EngineBuilder;
import com.agentframework.engine.core.ExecutionTraceStep;
import com.agentframework.engine.core.RunResult;
import com.agentframework.engine.toolexecutor.DefaultToolExecutor;
import com.agentframework.engine.toolexecutor.DefaultToolRegistry;
import com.agentframework.engine.toolexecutor.Tool;
import com.agentframework.engine.toolexecutor.ToolContext;
import com.agentframework.engine.toolexecutor.ToolInput;
import com.agentframework.engine.toolexecutor.ToolResult;
import com.agentframework.infra.modelgateway.ScriptedModelProvider;
import com.agentframework.runtime.session.Input;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.customer.platform.business.engine.outcome.OutcomeKind;
import com.jjx.customer.platform.business.engine.outcome.RunOutcomeMapper;
import com.jjx.customer.platform.business.knowledge.node.KbClassifyExecutor;
import com.jjx.customer.platform.business.knowledge.workflow.KnowledgeReactGraphFactory;
import com.jjx.customer.platform.business.knowledge.node.KbFinishExecutor;
import com.jjx.customer.platform.business.knowledge.workflow.KnowledgeQaGraphFactory;
import com.jjx.customer.platform.business.knowledge.node.KbCritiqueExecutor;
import com.jjx.customer.platform.business.knowledge.node.KbNormalizeExecutor;
import com.jjx.customer.platform.business.knowledge.node.KbRewriteExecutor;
import com.jjx.customer.platform.business.knowledge.node.KbRouteExecutor;
import com.jjx.customer.platform.business.knowledge.node.KbShortCircuitExecutor;
import com.jjx.customer.platform.business.ops.executor.ActExecutor;
import com.jjx.customer.platform.business.knowledge.intent.IntentClassifier;
import com.jjx.customer.platform.business.knowledge.normalize.QueryRewriter;
import com.jjx.customer.platform.intent.IntentResult;
import com.jjx.customer.platform.routing.RouteRegistry;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * knowledge 双图端到端（内存引擎 + 脚本模型）：
 *
 * <ul>
 *   <li>knowledge 轴 = 查询理解链（归一化 → 意图识别 → 路由 → 闸门 → 问题重写）→ 单次 TOOL 检索
 *       → 充分性判定 → 终态（命中进 kb_chunks_data）</li>
 *   <li>反思环 = 命中不足时扩词重写重查，撞迭代上限后收尾</li>
 *   <li>闸门短路 = 非检索意图不进检索链</li>
 *   <li>react_loop 轴 = 同一段查询理解链 + think→act→decide 工具循环（跨轮命中累积进 react_tool_chunks）</li>
 * </ul>
 */
class KnowledgeGraphTest {

    /** 测试用的短路域清单：只有问候（与 AgentProperties 默认一致）。 */
    private static final List<String> SHORT_CIRCUIT_DOMAINS = List.of("greeting");

    /** 固定返回一条命中的检索工具替身（与 RetrievalTool 的 data 契约一致）。 */
    private static Tool stubRetrievalTool() {
        return stubRetrievalTool(1);
    }

    /** @param hitCount 每次调用返回的命中条数（控制充分性判定的走向） */
    private static Tool stubRetrievalTool(int hitCount) {
        return new Tool() {
            @Override
            public String id() {
                return "retrieve_knowledge";
            }

            @Override
            public ToolSchema schema() {
                return ToolSchema.of(id(), "检索知识库", ToolParameter.required("query", "string"));
            }

            @Override
            public ToolResult invoke(ToolInput input, ToolContext context) {
                List<Map<String, Object>> chunks = new java.util.ArrayList<>();
                for (int i = 1; i <= hitCount; i++) {
                    chunks.add(Map.of("ref", i, "content", "发票冲红操作手册片段 " + i, "score", 0.92,
                            "originalScore", 0.71, "channel", "VECTOR", "docName", "发票手册"));
                }
                return ToolResult.ok("[ref=1] 发票冲红操作手册片段…", Map.of("chunks", chunks));
            }
        };
    }

    /** 固定域的知识意图分类器替身（无 LLM）。 */
    private static IntentClassifier fixedIntent(String domain) {
        return question -> IntentResult.builder().domain(domain).confidence(0.9)
                .reason("测试替身").build();
    }

    /** 首轮透传的改写器：无历史无补充时不会触碰 chatClient，故可传 null。 */
    private static QueryRewriter passthroughRewriter() {
        return new QueryRewriter(null, null);
    }

    /** 挂上查询理解链的六个执行器（两图共用，测试里逐个装配）。 */
    private static EngineBuilder withQueryUnderstanding(EngineBuilder builder, String domain) {
        return builder
                .nodeExecutor(KnowledgeQaGraphFactory.NORMALIZE_EXECUTOR, new KbNormalizeExecutor())
                .nodeExecutor(KnowledgeQaGraphFactory.CLASSIFY_EXECUTOR,
                        new KbClassifyExecutor(fixedIntent(domain)))
                .nodeExecutor(KnowledgeQaGraphFactory.ROUTE_EXECUTOR,
                        new KbRouteExecutor(new RouteRegistry(List.of())))
                .nodeExecutor(KnowledgeQaGraphFactory.SHORTCIRCUIT_EXECUTOR, new KbShortCircuitExecutor())
                .nodeExecutor(KnowledgeQaGraphFactory.REWRITE_EXECUTOR,
                        new KbRewriteExecutor(passthroughRewriter()));
    }

    /** 轨迹里的节点序列（断言"这条链真的跑过"）。 */
    private static List<String> nodeIds(RunResult result) {
        return result.executionTrace().stream().map(ExecutionTraceStep::nodeId).toList();
    }

    @Test
    void knowledge轴_查询理解链完整可见_命中进派生槽() {
        Engine engine = withQueryUnderstanding(EngineBuilder.create()
                .tool(stubRetrievalTool())
                .workflow(KnowledgeQaGraphFactory.create(3, SHORT_CIRCUIT_DOMAINS))
                .nodeExecutor(KbFinishExecutor.EXECUTOR_REF, new KbFinishExecutor())
                .nodeExecutor(KnowledgeQaGraphFactory.CRITIQUE_EXECUTOR,
                        new KbCritiqueExecutor(1, 0.3))
                .loopGuard(KnowledgeQaGraphFactory.ITERATION_GUARD, 3), "knowledge")
                .agent(AgentDefinition.builder("knowledge").workflow("knowledge_qa").build())
                .build();

        RunResult result = engine.run("knowledge",
                new Input("发票冲红失败怎么处理", Map.of(),
                        Map.of("question", "发票冲红失败怎么处理")));

        assertTrue(result.successful(), () -> "slots=" + result.slots());
        assertEquals(OutcomeKind.DIRECT, RunOutcomeMapper.kindOf(result));

        // 轨迹必须完整：先前这些步骤在编排层，轨迹里只剩 kb_retrieve/kb_done 两步
        assertEquals(List.of(
                "kb_normalize", "kb_classify", "kb_route", "kb_route_gate",
                "kb_rewrite", "kb_retrieve", "kb_critique", "kb_done"), nodeIds(result));

        assertEquals("发票冲红失败怎么处理",
                result.slots().get(KnowledgeQaGraphFactory.NORMALIZED_QUERY_SLOT));
        assertEquals("knowledge", result.slots().get(KnowledgeQaGraphFactory.INTENT_SLOT));
        assertEquals("knowledge", result.slots().get(KnowledgeQaGraphFactory.ROUTE_TARGET_SLOT));

        Object data = result.slots().get(KnowledgeQaGraphFactory.CHUNKS_DATA_SLOT);
        assertTrue(data instanceof Map<?, ?> map && map.get("chunks") instanceof List<?> chunks
                && !chunks.isEmpty(), () -> "kb_chunks_data 缺失，实际 slots=" + result.slots());
    }

    @Test
    void 反思环_命中不足时扩词重查_撞上限后收尾() {
        Engine engine = withQueryUnderstanding(EngineBuilder.create()
                .tool(stubRetrievalTool(1))
                .workflow(KnowledgeQaGraphFactory.create(3, SHORT_CIRCUIT_DOMAINS))
                .nodeExecutor(KbFinishExecutor.EXECUTOR_REF, new KbFinishExecutor())
                // 阈值 2 而只命中 1 条 → 永远判不充分 → 每轮都回边重写
                .nodeExecutor(KnowledgeQaGraphFactory.CRITIQUE_EXECUTOR,
                        new KbCritiqueExecutor(2, 0.3))
                .loopGuard(KnowledgeQaGraphFactory.ITERATION_GUARD, 3), "knowledge")
                .agent(AgentDefinition.builder("knowledge").workflow("knowledge_qa").build())
                .build();

        RunResult result = engine.run("knowledge",
                new Input("发票冲红失败怎么处理", Map.of(),
                        Map.of("question", "发票冲红失败怎么处理")));

        assertTrue(result.successful(), () -> "slots=" + result.slots());
        List<String> nodes = nodeIds(result);
        long retrieves = nodes.stream().filter("kb_retrieve"::equals).count();
        // 断言行为而非守卫的内部计数：没有反思环时检索恒为 1 次，有环则必须 >1 且被上限兜住
        assertTrue(retrieves > 1 && retrieves <= 3,
                "检索应在 (1, 迭代上限] 之间重复，实际 " + retrieves + " 次，轨迹=" + nodes);
        assertEquals(retrieves, nodes.stream().filter("kb_rewrite"::equals).count(),
                "每次不足都要重写，实际轨迹=" + nodes);
        assertEquals("kb_done", nodes.get(nodes.size() - 1), "撞上限后应正常收尾，实际轨迹=" + nodes);

        // 扩词确实作用到了查询上：第 2 轮起并入扩词表首项
        Object rewritten = result.slots().get(KnowledgeQaGraphFactory.REWRITTEN_QUERY_SLOT);
        assertTrue(String.valueOf(rewritten).contains("处理 流程 步骤 规范"),
                "重写轮应扩词，实际 rewritten_query=" + rewritten);
        // 充分性未达标：0.5 = min(1, 1/2)
        assertEquals(0.5, (Double) result.slots().get(KnowledgeQaGraphFactory.SUFFICIENCY_SLOT), 1e-6);
    }

    @Test
    void 闸门_问候短路不进检索链() {
        Engine engine = withQueryUnderstanding(EngineBuilder.create()
                .tool(stubRetrievalTool())
                .workflow(KnowledgeQaGraphFactory.create(3, SHORT_CIRCUIT_DOMAINS))
                .nodeExecutor(KbFinishExecutor.EXECUTOR_REF, new KbFinishExecutor())
                .nodeExecutor(KnowledgeQaGraphFactory.CRITIQUE_EXECUTOR,
                        new KbCritiqueExecutor(1, 0.3))
                .loopGuard(KnowledgeQaGraphFactory.ITERATION_GUARD, 3), "greeting")
                .agent(AgentDefinition.builder("knowledge").workflow("knowledge_qa").build())
                .build();

        RunResult result = engine.run("knowledge",
                new Input("你好", Map.of(), Map.of("question", "你好")));

        assertTrue(result.successful());
        assertEquals(List.of("kb_normalize", "kb_classify", "kb_route", "kb_route_gate", "kb_shortcircuit"),
                nodeIds(result));
        assertFalse(Boolean.TRUE.equals(result.slots().get(KnowledgeQaGraphFactory.NEEDS_RETRIEVAL_SLOT)),
                "问候域不应要求检索");
    }

    /**
     * 闲聊域<b>不再</b>短路：分类器的域是按本仓语料划的，域外知识问题（"影视拍摄技巧"、
     * "水龙头一直滴水"）没有归属域，会落到 chitchat 这个兜底上——短路就等于一条资料都不查。
     */
    @Test
    void 闸门_闲聊仍然进检索链() {
        Engine engine = withQueryUnderstanding(EngineBuilder.create()
                .tool(stubRetrievalTool())
                .workflow(KnowledgeQaGraphFactory.create(3, SHORT_CIRCUIT_DOMAINS))
                .nodeExecutor(KbFinishExecutor.EXECUTOR_REF, new KbFinishExecutor())
                .nodeExecutor(KnowledgeQaGraphFactory.CRITIQUE_EXECUTOR,
                        new KbCritiqueExecutor(1, 0.3))
                .loopGuard(KnowledgeQaGraphFactory.ITERATION_GUARD, 3), "chitchat")
                .agent(AgentDefinition.builder("knowledge").workflow("knowledge_qa").build())
                .build();

        RunResult result = engine.run("knowledge",
                new Input("I'm curious about filming techniques", Map.of(),
                        Map.of("question", "I'm curious about filming techniques")));

        assertTrue(result.successful());
        List<String> nodes = nodeIds(result);
        assertFalse(nodes.contains("kb_shortcircuit"), "闲聊域不应再短路，实际轨迹=" + nodes);
        assertTrue(nodes.contains("kb_retrieve"), "闲聊域也应当经过检索，实际轨迹=" + nodes);
    }

    /** 改写策略：OFF 一律透传，FORCE 一律尝试改写（本测试无 LLM，走降级但分支必须走到）。 */
    @Test
    void 改写策略_OFF透传_FORCE尝试改写() {
        assertEquals("透传", outputOfRewriteStep(RewritePolicy.OFF));
        assertEquals("尝试过", outputOfRewriteStep(RewritePolicy.FORCE));
    }

    /** 跑一轮取 kb_rewrite 那一步的输出，据此判断策略分支走没走到。 */
    private static String outputOfRewriteStep(RewritePolicy policy) {
        Engine engine = withQueryUnderstanding(EngineBuilder.create()
                .tool(stubRetrievalTool())
                .workflow(KnowledgeQaGraphFactory.create(3, SHORT_CIRCUIT_DOMAINS))
                .nodeExecutor(KbFinishExecutor.EXECUTOR_REF, new KbFinishExecutor())
                .nodeExecutor(KnowledgeQaGraphFactory.CRITIQUE_EXECUTOR,
                        new KbCritiqueExecutor(1, 0.3))
                .loopGuard(KnowledgeQaGraphFactory.ITERATION_GUARD, 3), "knowledge")
                .agent(AgentDefinition.builder("knowledge").workflow("knowledge_qa").build())
                .build();

        RunResult result = engine.run("knowledge", new Input("发票冲红失败怎么处理", Map.of(),
                Map.of("question", "发票冲红失败怎么处理",
                        KnowledgeQaGraphFactory.REWRITE_POLICY_SLOT, policy.name())));
        String output = result.executionTrace().stream()
                .filter(step -> KnowledgeQaGraphFactory.REWRITE_NODE.equals(step.nodeId()))
                .map(ExecutionTraceStep::output)
                .findFirst().orElse("");
        if (output.contains("原样透传")) {
            return "透传";
        }
        return output.contains("改写未生效") ? "尝试过" : "未知:" + output;
    }

    @Test
    void react轴_两轮工具循环_命中累积_答案收尾() {
        ScriptedModelProvider scripted = new ScriptedModelProvider()
                .enqueueText("{\"tool\":\"retrieve_knowledge\",\"args\":{\"query\":\"发票冲红\"}}")
                .enqueueText("{\"answer\":\"按手册第 3 步冲红即可 [ref=1]\"}");
        DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(stubRetrievalTool());

        Engine engine = withQueryUnderstanding(EngineBuilder.create()
                .toolRegistry(registry)
                .modelProvider(scripted)
                .prompt(PromptDefinition.template(KbPrompts.REACT_THINK_ASSET, "围绕问题检索并作答"))
                .workflow(KnowledgeReactGraphFactory.create(4, SHORT_CIRCUIT_DOMAINS))
                .nodeExecutor(KbFinishExecutor.EXECUTOR_REF, new KbFinishExecutor())
                .nodeExecutor(KnowledgeReactGraphFactory.ACT_EXECUTOR,
                        new ActExecutor("react", "工具循环检索", Set.of("retrieve_knowledge"),
                                registry, new DefaultToolExecutor(registry, null, null, null),
                                new ObjectMapper(), 24))
                .loopGuard(KnowledgeReactGraphFactory.LOOP_GUARD, 4), "knowledge")
                .agent(AgentDefinition.builder("react_loop").workflow("knowledge_qa_react").build())
                .build();

        RunResult result = engine.run("react_loop",
                new Input("发票冲红失败怎么处理", Map.of(), Map.of("question", "发票冲红失败怎么处理")));

        assertTrue(result.successful(), () -> "slots=" + result.slots());
        assertEquals(OutcomeKind.DIRECT, RunOutcomeMapper.kindOf(result));
        Object chunks = result.slots().get("react_tool_chunks");
        assertTrue(chunks instanceof List<?> list && list.size() == 1, "跨轮命中应累积 1 条");
        assertEquals("按手册第 3 步冲红即可 [ref=1]", result.slots().get("react_stage_output"));
        // 工具循环轴同样先过查询理解链（否则同一句问候在两条轴上行为不一致）
        assertTrue(nodeIds(result).containsAll(
                        List.of("kb_normalize", "kb_classify", "kb_route", "kb_route_gate")),
                () -> "react 轴缺查询理解链，实际轨迹=" + nodeIds(result));
    }
}
