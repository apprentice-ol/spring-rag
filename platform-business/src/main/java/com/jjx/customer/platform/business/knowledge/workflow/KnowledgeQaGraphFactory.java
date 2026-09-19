package com.jjx.customer.platform.business.knowledge.workflow;
import com.jjx.customer.platform.business.knowledge.node.KbFinishExecutor;

import com.agentframework.definition.node.ConditionNodeDefinition;
import com.agentframework.definition.node.ConditionNodeDefinition.Branch;
import com.agentframework.definition.node.CustomNodeDefinition;
import com.agentframework.definition.node.NodeMeta;
import com.agentframework.definition.node.ToolNodeDefinition;
import com.agentframework.definition.region.LoopConvergence;
import com.agentframework.definition.region.Paradigm;
import com.agentframework.definition.region.RegionDefinition;
import com.agentframework.definition.region.RegionLoop;
import com.agentframework.definition.workflow.SlotType;
import com.agentframework.definition.workflow.WorkflowBuilder;
import com.agentframework.definition.workflow.WorkflowDefinition;
import com.jjx.customer.platform.knowledge.tools.RetrievalTool;
import java.util.Map;

/**
 * 知识问答图（查询理解链 + 检索反思环，对齐 agent-framework 的 {@code RagWorkflowFactory}）。
 *
 * <pre>
 * kb_normalize → kb_classify → kb_route → kb_route_gate ─┬─（闲聊 / 转诊断）→ kb_shortcircuit（终态）
 *                                                         └─ kb_rewrite → kb_retrieve → kb_critique ─┬─（充分）→ kb_done（终态）
 *                                                                                                     └─（不足）→ kb_rewrite
 * </pre>
 *
 * <p><b>为什么把查询理解链放进图</b>：先前 normalize/classify/route/rewrite 都在编排层
 * {@code ChatOrchestrator} 里过程式执行，它们不产生 {@code ExecutionTraceStep}，
 * 于是前端轨迹里只看得到 {@code kb_retrieve → kb_done} 两步——一次问答真正做过的事
 * （归一化、意图识别、路由、问题重写）全部不可见。图是唯一事实源，这些步骤就该在图里。</p>
 *
 * <p><b>反思环</b>：{@code r_kb_reflection = [kb_retrieve, kb_critique, kb_rewrite]} 挂循环上限、
 * 收敛判定（观察 {@code sufficiency} 槽位）与迭代守卫；{@code kb_critique} 在
 * {@code [kb_done, kb_rewrite]} 白名单内动态自决——命中不足时扩词重查，
 * 而不是一次不中就降到「未检索到相关资料」。</p>
 *
 * <p><b>分工边界</b>：编排层仍然负责「这一轮到底走哪条路」的粗判（活动会话恢复、
 * traceId 正则短路、用户显式选范式、指代悬空反问）与交付侧（答案缓存、降级闸、
 * 上下文装配、流式输出、落库）；闸门之后的一切在图内完成，结果经槽位回传编排层分派。</p>
 */
public final class KnowledgeQaGraphFactory {

    /** 工作流 id（与旧 {@code KnowledgeQaWorkflow.ID} 一致，指纹与轨迹口径不变）。 */
    public static final String WORKFLOW_ID = "knowledge_qa";

    /** 归一化节点 id。 */
    public static final String NORMALIZE_NODE = "kb_normalize";
    /** 意图识别节点 id。 */
    public static final String CLASSIFY_NODE = "kb_classify";
    /** 路由判定节点 id（规则表求值，产出 route_target）。 */
    public static final String ROUTE_NODE = "kb_route";
    /** 路由闸门节点 id（按 route_target 分流）。 */
    public static final String ROUTE_GATE_NODE = "kb_route_gate";
    /** 短路终态节点 id（闲聊直接回复 / 转运维诊断，都不在本图内生成内容）。 */
    public static final String SHORTCIRCUIT_NODE = "kb_shortcircuit";
    /** 问题重写节点 id。 */
    public static final String REWRITE_NODE = "kb_rewrite";
    /** 检索节点 id。 */
    public static final String RETRIEVE_NODE = "kb_retrieve";
    /** 充分性判定节点 id。 */
    public static final String CRITIQUE_NODE = "kb_critique";
    /** 终态节点 id。 */
    public static final String DONE_NODE = "kb_done";

    /** 归一化执行器注册名。 */
    public static final String NORMALIZE_EXECUTOR = "kb-normalize";
    /** 意图识别执行器注册名。 */
    public static final String CLASSIFY_EXECUTOR = "kb-classify";
    /** 路由判定执行器注册名。 */
    public static final String ROUTE_EXECUTOR = "kb-route";
    /** 短路终态执行器注册名。 */
    public static final String SHORTCIRCUIT_EXECUTOR = "kb-shortcircuit";
    /** 问题重写执行器注册名。 */
    public static final String REWRITE_EXECUTOR = "kb-rewrite";
    /** 充分性判定执行器注册名。 */
    public static final String CRITIQUE_EXECUTOR = "kb-critique";

    /** 反思区域 id。 */
    public static final String REFLECTION_REGION = "r_kb_reflection";

    /** 迭代守卫名（critique 节点 meta.guardRefs 引用）。 */
    public static final String ITERATION_GUARD = "kb-max-iterations";

    /** 检索命中的观测文本槽位（[ref=N] 预览）。 */
    public static final String CHUNKS_SLOT = "kb_chunks";

    /** 检索命中的结构化明细槽位（{code kb_chunks}_data 派生槽，TOOL 节点自动写入）。 */
    public static final String CHUNKS_DATA_SLOT = CHUNKS_SLOT + "_data";

    /**
     * 跨轮累积的检索命中槽位（反思环各轮命中合并、按分片标识去重）。
     *
     * <p>{@link #CHUNKS_DATA_SLOT} 是 TOOL 节点每轮<b>覆盖写</b>的单轮产物。出口层早先直接读它，
     * 于是反思轮一旦空手而归，就把首轮的正确命中一并抹掉了——实测「命中 1 条（相关度 0.62）
     * → 判不充分 → 第 2/3 轮被扩词污染后 0 条 → 最终"无命中"」，首轮的成功白丢。
     * 本槽由 {@code kb_critique} 每轮并入，出口层读它，语义与 react 轴的
     * {@code react_tool_chunks}（Act 执行器跨轮 append）对齐。</p>
     */
    public static final String CHUNKS_ALL_SLOT = CHUNKS_SLOT + "_all";

    /** 问题槽位（runner 经 Input.slots 预置；react 轴 think 模板引用）。 */
    public static final String QUESTION_SLOT = "question";

    /** 归一化查询槽位（kb_normalize 写入；编排层为答案缓存 key 复用同一纯函数的结果）。 */
    public static final String NORMALIZED_QUERY_SLOT = "normalized_query";

    /** 改写查询槽位（kb_rewrite 写入；检索工具参数引用——改写真正作用于检索）。 */
    public static final String REWRITTEN_QUERY_SLOT = "rewritten_query";

    /** 意图域槽位（knowledge / greeting / chitchat / web_search / ops_diagnose…）。 */
    public static final String INTENT_SLOT = "intent";

    /** 意图置信度槽位（ops 域规则按它判定是否劫持）。 */
    public static final String INTENT_CONFIDENCE_SLOT = "intent_confidence";

    /** 联网搜索标记槽位（检索模式参数，随意图分类产出）。 */
    public static final String NEEDS_WEB_SEARCH_SLOT = "needs_web_search";

    /**
     * 检索需求标记槽位（{@code IntentResult.needsRetrieval()} 的派生值：域不在非检索域集合即 true）。
     *
     * <p>闸门要靠它拦住问候/闲聊——这类问题的 {@code route_target} 仍是 {@code knowledge}
     * （没有哪条路由规则会命中它），只看 route_target 会一路滑进检索链，
     * 让一句「你好」去查知识库。</p>
     */
    public static final String NEEDS_RETRIEVAL_SLOT = "needs_retrieval";

    /**
     * 意图分类理由槽位。
     *
     * <p>刻意只落标量、不落 {@code IntentResult} 对象：槽位写入会随
     * {@code ExecutionTraceStep.toDocument()} 进 SSE 轨迹，塞对象等于把一整坨
     * 内部结构摊给前端；路由节点按这几个标量重建 {@code IntentResult} 即可。</p>
     */
    public static final String INTENT_REASON_SLOT = "intent_reason";

    /** 路由目标槽位（knowledge = 留在本图检索；其余值由编排层接手）。 */
    public static final String ROUTE_TARGET_SLOT = "route_target";

    /**
     * 强制检索槽位（BOOLEAN）：置真时路由节点不再把本轮转给别的 agent。
     *
     * <p>评测专用。评测要量的是"检索质量"，而领域分类器是另一个被测对象——一道通用知识题
     * 被判成 {@code ops_diagnose} 就会被转走，{@code EvalRunner} 拿到空 chunks 静默记 0 召回，
     * 分数掉的是分类器的域覆盖，不是检索。轨迹里会注明"评测强制检索"，不藏这个开关。</p>
     */
    public static final String FORCE_RETRIEVAL_SLOT = "force_retrieval";

    /** 路由预填槽位槽位（如正则提取的 trace_id，随目标 agent 的槽位目录合并）。 */
    public static final String ROUTE_PREFILL_SLOT = "route_prefill";

    /** 改写轮次槽位（0 = 首轮；同时作为反思循环的计数槽位）。 */
    public static final String REWRITE_ROUND_SLOT = "rewrite_round";

    /**
     * 改写策略槽位（{@code AUTO} / {@code OFF} / {@code FORCE}）。
     *
     * <p>调用方预置：线上不置（缺省 AUTO），评测按 {@code rewrite} 开关置 FORCE/OFF——
     * 这是"改写有没有用"这个对照实验唯一的注入点。</p>
     */
    public static final String REWRITE_POLICY_SLOT = "rewrite_policy";

    /** 充分性槽位（0~1，同时作为反思循环的收敛观测量）。 */
    public static final String SUFFICIENCY_SLOT = "sufficiency";

    /** 充分性判定理由槽位（critique 节点输出）。 */
    public static final String CRITIQUE_REASON_SLOT = "critique_reason";

    /** 会话历史槽位（runner 预置；kb_rewrite 消解指代用，无历史则首轮透传）。 */
    public static final String HISTORY_SLOT = "history";

    /** 用户追问补充槽位（runner 预置）。 */
    public static final String CLARIFY_SLOT = "user_clarify";

    /** 正则提取的 traceId 槽位（路由规则短路判定用）。 */
    public static final String TRACE_ID_SLOT = "extracted_trace_id";

    /** 用户显式选择的范式槽位（路由规则用）。 */
    public static final String AGENT_CHOICE_SLOT = "agent_choice";

    /** 活动追问会话的 agentType 槽位（路由规则用）。 */
    public static final String SESSION_AGENT_SLOT = "session_agent_type";

    /** LLM 调用计数槽位（跟踪用；knowledge 线改写/分类各计一次）。 */
    public static final String LLM_CALLS_SLOT = "llm_calls";

    /** 短路终态与终态节点的输出槽位。 */
    public static final String FINAL_OUTPUT_SLOT = "final_output";

    private KnowledgeQaGraphFactory() {
    }

    /**
     * 知识问答图（单次检索 + 反思环）。
     *
     * @param maxRewriteRounds     反思循环上限（检索不充分时最多重写重查几次）
     * @param shortCircuitDomains  短路直答的意图域（见 {@code AgentProperties#shortCircuitDomains}）
     * @return 工作流定义（build 期完成校验）
     */
    public static WorkflowDefinition create(int maxRewriteRounds, java.util.List<String> shortCircuitDomains) {
        return withQueryUnderstanding(WorkflowBuilder.create(WORKFLOW_ID, "3.0.0"), REWRITE_NODE,
                shortCircuitDomains)
                // ---------- 检索反思链 ----------
                .node(CustomNodeDefinition.of(REWRITE_NODE, REWRITE_EXECUTOR, REWRITTEN_QUERY_SLOT))
                .node(ToolNodeDefinition.of(RETRIEVE_NODE, RetrievalTool.TOOL_ID, CHUNKS_SLOT)
                        .withArgument("query", "${slots." + REWRITTEN_QUERY_SLOT + "}"))
                .node(new CustomNodeDefinition(CRITIQUE_NODE, CRITIQUE_EXECUTOR, Map.of(),
                        CRITIQUE_REASON_SLOT, NodeMeta.empty().withGuards(ITERATION_GUARD)))
                .node(new CustomNodeDefinition(DONE_NODE, KbFinishExecutor.EXECUTOR_REF, Map.of(),
                        FINAL_OUTPUT_SLOT, NodeMeta.empty().withAttribute("terminal", true)))
                .edge(REWRITE_NODE, RETRIEVE_NODE)
                .edge(RETRIEVE_NODE, CRITIQUE_NODE)
                .edge(CRITIQUE_NODE, DONE_NODE)
                .edge(CRITIQUE_NODE, REWRITE_NODE)
                // 反思区域：循环上限 + 收敛（观察 sufficiency）+ 计数槽位
                .region(RegionDefinition.of(REFLECTION_REGION, Paradigm.REFLECTION,
                                RETRIEVE_NODE, CRITIQUE_NODE, REWRITE_NODE)
                        .withLoop(RegionLoop.of(RETRIEVE_NODE, CRITIQUE_NODE, Math.max(1, maxRewriteRounds))
                                .withConvergence(LoopConvergence.of(SUFFICIENCY_SLOT, 0.05))
                                .withCounterSlot(REWRITE_ROUND_SLOT)))
                // kb_critique 动态边白名单：充分→收尾，不足→扩词重检
                .dynamic(CRITIQUE_NODE, DONE_NODE, REWRITE_NODE)
                .slot(REWRITTEN_QUERY_SLOT, SlotType.STRING)
                .slot(REWRITE_ROUND_SLOT, SlotType.NUMBER)
                .slot(REWRITE_POLICY_SLOT, SlotType.STRING)
                .slot(CHUNKS_SLOT, SlotType.STRING)
                .slot(CHUNKS_DATA_SLOT, SlotType.OBJECT)
                .slot(CHUNKS_ALL_SLOT, SlotType.ARRAY)
                .slot(SUFFICIENCY_SLOT, SlotType.NUMBER)
                .slot(CRITIQUE_REASON_SLOT, SlotType.STRING)
                .build();
    }

    /**
     * 给任意工作流前置「查询理解链」，闸门放行后进入 {@code onKnowledgeNode}。
     *
     * <p>两条范式轴（knowledge 的检索链 / react_loop 的工具循环）都要先读懂问题再决定查不查，
     * 这一段的节点、边、槽位在两处必须逐字一致——否则同一句「你好」在两条轴上一个走闲聊、
     * 一个掉进工具循环。抽成构件就是为了让它们不可能不一致。</p>
     *
     * @param builder             工作流构建器
     * @param onKnowledgeNode     路由判定为 knowledge 时的下一跳（检索链是 kb_rewrite，工具循环是 react_think）
     * @param shortCircuitDomains 短路直答的意图域；空清单 = 不短路（一切都走检索）
     * @return 追加了查询理解链的构建器
     */
    public static WorkflowBuilder withQueryUnderstanding(WorkflowBuilder builder, String onKnowledgeNode,
                                                         java.util.List<String> shortCircuitDomains) {
        // 分支按域清单现拼：域清单是配置项，闸门表达式却必须编译期内联成字符串。
        // 清单为空时整个分支不出现——留一条恒真的短路分支等于把所有请求都打发掉。
        java.util.List<Branch> branches = new java.util.ArrayList<>();
        branches.add(Branch.when(SHORTCIRCUIT_NODE, "slots." + ROUTE_TARGET_SLOT + " != 'knowledge'"));
        if (shortCircuitDomains != null && !shortCircuitDomains.isEmpty()) {
            String domainMatch = shortCircuitDomains.stream()
                    .filter(d -> d != null && !d.isBlank())
                    .map(d -> "slots." + INTENT_SLOT + " == '" + d.trim() + "'")
                    .collect(java.util.stream.Collectors.joining(" || "));
            if (!domainMatch.isEmpty()) {
                // force_retrieval 置真时这条不生效：评测要量每一道题的检索，
                // 被判成问候就跳过的话，这道题会以"没检索过"的样子混进检索分里
                branches.add(Branch.when(SHORTCIRCUIT_NODE,
                        "(" + domainMatch + ") && slots." + FORCE_RETRIEVAL_SLOT + " != true"));
            }
        }
        branches.add(Branch.otherwise(onKnowledgeNode));

        return builder
                .node(CustomNodeDefinition.of(NORMALIZE_NODE, NORMALIZE_EXECUTOR, NORMALIZED_QUERY_SLOT))
                .node(CustomNodeDefinition.of(CLASSIFY_NODE, CLASSIFY_EXECUTOR, INTENT_SLOT))
                .node(CustomNodeDefinition.of(ROUTE_NODE, ROUTE_EXECUTOR, ROUTE_TARGET_SLOT))
                // 闸门：目标不是本图（转诊断）或命中短路域（默认只有问候）才走短路终态
                .node(ConditionNodeDefinition.of(ROUTE_GATE_NODE,
                        branches.toArray(new Branch[0])))
                .node(new CustomNodeDefinition(SHORTCIRCUIT_NODE, SHORTCIRCUIT_EXECUTOR, Map.of(),
                        FINAL_OUTPUT_SLOT, NodeMeta.empty().withAttribute("terminal", true)))
                .edge(NORMALIZE_NODE, CLASSIFY_NODE)
                .edge(CLASSIFY_NODE, ROUTE_NODE)
                .edge(ROUTE_NODE, ROUTE_GATE_NODE)
                .edge(ROUTE_GATE_NODE, SHORTCIRCUIT_NODE)
                .edge(ROUTE_GATE_NODE, onKnowledgeNode)
                .slot(QUESTION_SLOT, SlotType.STRING)
                .slot(NORMALIZED_QUERY_SLOT, SlotType.STRING)
                .slot(INTENT_SLOT, SlotType.STRING)
                .slot(INTENT_CONFIDENCE_SLOT, SlotType.NUMBER)
                .slot(NEEDS_WEB_SEARCH_SLOT, SlotType.BOOLEAN)
                .slot(NEEDS_RETRIEVAL_SLOT, SlotType.BOOLEAN)
                .slot(INTENT_REASON_SLOT, SlotType.STRING)
                .slot(ROUTE_TARGET_SLOT, SlotType.STRING)
                .slot(ROUTE_PREFILL_SLOT, SlotType.OBJECT)
                .slot(FORCE_RETRIEVAL_SLOT, SlotType.BOOLEAN)
                .slot(HISTORY_SLOT, SlotType.STRING)
                .slot(CLARIFY_SLOT, SlotType.STRING)
                .slot(TRACE_ID_SLOT, SlotType.STRING)
                .slot(AGENT_CHOICE_SLOT, SlotType.STRING)
                .slot(SESSION_AGENT_SLOT, SlotType.STRING)
                .slot(FINAL_OUTPUT_SLOT, SlotType.STRING);
    }
}
