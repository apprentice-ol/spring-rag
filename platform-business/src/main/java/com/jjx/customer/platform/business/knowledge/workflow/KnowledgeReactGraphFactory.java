package com.jjx.customer.platform.business.knowledge.workflow;
import com.jjx.customer.platform.business.knowledge.node.KbFinishExecutor;
import com.jjx.customer.platform.business.workflow.common.EscalateTerminal;
import com.jjx.customer.platform.business.knowledge.KbPrompts;

import com.agentframework.definition.node.ConditionNodeDefinition;
import com.agentframework.definition.node.ConditionNodeDefinition.Branch;
import com.agentframework.definition.node.CustomNodeDefinition;
import com.agentframework.definition.node.LlmNodeDefinition;
import com.agentframework.definition.node.NodeMeta;
import com.agentframework.definition.node.TerminalKind;
import com.agentframework.definition.region.Paradigm;
import com.agentframework.definition.region.RegionDefinition;
import com.agentframework.definition.region.RegionLoop;
import com.agentframework.definition.workflow.SlotType;
import com.agentframework.definition.workflow.WorkflowBuilder;
import com.agentframework.definition.workflow.WorkflowDefinition;
import com.jjx.customer.platform.business.workflow.common.ActExecutor;
import java.util.Map;

/**
 * 工具循环检索图（react_loop 轴，替代旧 {@code KnowledgeQaReactWorkflow} 的 LOOP 单阶段）。
 *
 * <pre>
 * kb_normalize → kb_classify → kb_route → kb_route_gate ─┬─（闲聊 / 转诊断）→ kb_shortcircuit（终态）
 *                                                         └─ react_think(LLM) → react_act(CUSTOM: kb-act) → react_decide ─┬─(react_has_calls &gt; 0)→ react_think（有界）
 *                                                                                                                          └─(无调用)→ kb_done(终态)
 * </pre>
 *
 * <p>与 knowledge 轴的差异只在"节点形态"：这里是 think→act→decide 工具循环
 * （{@code ActExecutor} 白名单 {retrieve_knowledge}），那里是单次 TOOL 节点。
 * 跨轮命中累积在 {@code react_tool_chunks}（Act 执行器 data 通道），出口语义与
 * knowledge 轴一致。升级终态复用 ops 的 {@code escalate_node}（预算触顶不静默截断）。</p>
 *
 * <p><b>为什么两条轴都要挂查询理解链</b>：归一化/意图识别/路由本来在编排层，
 * 是两条轴共享的前置步骤；把它们移进图之后，任一轴漏挂都会让该轴失去「闲聊短路」
 * ——同一个「你好」在 knowledge 轴走闲聊、在 react_loop 轴却掉进工具循环。
 * 前置链由 {@link KnowledgeQaGraphFactory#withQueryUnderstanding} 统一提供。</p>
 */
public final class KnowledgeReactGraphFactory {

    /** 工作流 id（与旧 {@code KnowledgeQaReactWorkflow.ID} 一致）。 */
    public static final String WORKFLOW_ID = "knowledge_qa_react";

    /** think 节点 id。 */
    public static final String THINK_NODE = "react_think";

    /** act 节点 id（执行器注册名见 {@code #ACT_EXECUTOR}）。 */
    public static final String ACT_NODE = "react_act";

    /** act 执行器注册名（装配时挂 {@code ActExecutor}，前缀 react）。 */
    public static final String ACT_EXECUTOR = "kb-act";

    /** decide 节点 id。 */
    public static final String DECIDE_NODE = "react_decide";

    /** 工具循环 Region id。 */
    public static final String REGION = "r_react";

    /** 迭代守卫名（decide 节点引用）。 */
    public static final String LOOP_GUARD = "kb-react-max";

    /** 跨轮检索命中累积槽位（Region 前缀展开：react_tool_chunks）。 */
    public static final String TOOL_CHUNKS_SLOT = "react_tool_chunks";

    private KnowledgeReactGraphFactory() {
    }

    /**
     * @param maxSteps            循环上限（{@code rag.chat.agent.react-max-steps}）
     * @param shortCircuitDomains 短路直答的意图域（两轴共用，见 {@code KnowledgeQaGraphFactory}）
     * @return 工作流定义（build 期完成校验）
     */
    public static WorkflowDefinition create(int maxSteps, java.util.List<String> shortCircuitDomains) {
        return KnowledgeQaGraphFactory
                .withQueryUnderstanding(WorkflowBuilder.create(WORKFLOW_ID, "3.0.0"), THINK_NODE,
                        shortCircuitDomains)
                // Region 局部槽模板（短名写入按前缀展开为 react_xxx）
                .regionSlot("scratchpad", SlotType.STRING)
                .regionSlot("has_calls", SlotType.NUMBER)
                .regionSlot("observation", SlotType.STRING)
                .regionSlot("stage_output", SlotType.STRING)
                .regionSlot("retries", SlotType.NUMBER)
                .regionSlot("tool_chunks", SlotType.ARRAY)
                .slot("llm_calls", SlotType.NUMBER)
                .node(LlmNodeDefinition.of(THINK_NODE, KbPrompts.REACT_THINK_ASSET, "react_out"))
                .node(CustomNodeDefinition.of(ACT_NODE, ACT_EXECUTOR, "react_observation"))
                .node(ConditionNodeDefinition.of(DECIDE_NODE,
                        NodeMeta.empty().withGuards(LOOP_GUARD),
                        Branch.when(THINK_NODE, "slots.react_has_calls > 0"),
                        Branch.otherwise(KnowledgeQaGraphFactory.DONE_NODE)))
                .node(new CustomNodeDefinition(KnowledgeQaGraphFactory.DONE_NODE,
                        KbFinishExecutor.EXECUTOR_REF, Map.of(), "final_output",
                        NodeMeta.empty().withAttribute("terminal", true)
                                .withAttribute(TerminalKind.META_KEY, TerminalKind.FINISH.name())))
                .node(new CustomNodeDefinition(EscalateTerminal.NODE_ID,
                        EscalateTerminal.EXECUTOR_ID, Map.of(), "escalate_reason",
                        NodeMeta.empty().withAttribute("terminal", true)
                                .withAttribute(TerminalKind.META_KEY, TerminalKind.ESCALATE.name())))
                .edge(THINK_NODE, ACT_NODE)
                .edge(ACT_NODE, DECIDE_NODE)
                .edge(DECIDE_NODE, THINK_NODE)
                .edge(DECIDE_NODE, KnowledgeQaGraphFactory.DONE_NODE)
                .edge(ACT_NODE, EscalateTerminal.NODE_ID)
                .dynamic(ACT_NODE, DECIDE_NODE, EscalateTerminal.NODE_ID)
                .region(RegionDefinition.of(REGION, Paradigm.TOOL_CALL, THINK_NODE, ACT_NODE, DECIDE_NODE)
                        .withSlotPrefix("react")
                        .withLoop(RegionLoop.of(THINK_NODE, DECIDE_NODE, Math.max(1, maxSteps))
                                .withCounterSlot("react_round")))
                .build();
    }

    /** {@link ActExecutor} 的 react 实例构造参数（白名单单一工具）。 */
    public static final String PREFIX = "react";
}
