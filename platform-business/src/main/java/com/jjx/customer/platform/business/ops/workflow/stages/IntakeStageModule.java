package com.jjx.customer.platform.business.ops.workflow.stages;

import com.agentframework.definition.node.ConditionNodeDefinition;
import com.agentframework.definition.node.ConditionNodeDefinition.Branch;
import com.agentframework.definition.node.CustomNodeDefinition;
import com.agentframework.definition.node.NodeMeta;
import com.agentframework.definition.region.Paradigm;
import com.agentframework.definition.region.RegionDefinition;
import com.agentframework.definition.region.RegionLoop;
import com.agentframework.definition.workflow.SlotType;
import com.agentframework.definition.workflow.WorkflowBuilder;
import com.agentframework.engine.core.EngineBuilder;
import com.jjx.customer.platform.business.ops.workflow.OpsDiagnoseWorkflowFactory;
import com.jjx.customer.platform.business.ops.slot.OpsSlotCatalog;
import com.jjx.customer.platform.business.ops.slot.OpsSlotExtractor;
import com.jjx.customer.platform.business.ops.node.AskMissingExecutor;
import com.jjx.customer.platform.business.ops.node.AutoResolveExecutor;
import com.jjx.customer.platform.business.ops.node.SlotExtractExecutor;
import java.util.Set;

/**
 * 入口阶段：问齐环 + 自主补全（HUMAN_IN_LOOP）。
 *
 * <p>本阶段的一切都在这个文件里：抽取槽位 → 门禁 → 缺必填先自主补全
 * （规则 → LLM → 日志反查，见 {@link AutoResolveExecutor}）→ 仍缺才问齐兜底；
 * 槽位契约 = 业务七槽（目录）+ 问齐环控制槽；执行器 = 抽取 / 补全 / 问齐三件。</p>
 *
 * <pre>
 * extract_slots → slots_gate ─┬─（缺必填）→ auto_resolve → auto_gate ─┬─（补齐）→ inv_think
 *                             │                                        └─（仍缺）→ collect_slots
 *                             └─（齐备）→ inv_think     collect_slots ⇄ slots_regate（问齐环，3 轮有界）
 * </pre>
 */
public final class IntakeStageModule implements StageModule {

    /** 装配键。 */
    public static final String KEY = "intake";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public Set<String> provides() {
        return Set.of(OpsDiagnoseWorkflowFactory.SLOT_EXTRACT_EXECUTOR,
                OpsDiagnoseWorkflowFactory.AUTO_RESOLVE_EXECUTOR,
                OpsDiagnoseWorkflowFactory.ASK_MISSING_EXECUTOR);
    }

    @Override
    public void declareGraph(WorkflowBuilder workflowBuilder) {
        // 槽位：业务七槽（全流程输入契约）+ 问齐环控制槽 + 挂起恢复通道族
        OpsSlotCatalog.declareBusinessSlots(workflowBuilder);
        workflowBuilder.slot("missing_count", SlotType.NUMBER)   // 门禁判定
          .slot("inferred_slots", SlotType.STRING)  // 自主补全溯源
          .slot("user_clarify", SlotType.STRING)    // 问齐挂起恢复：Input.slots 回灌
          .slot("pending_ask", SlotType.STRING)     // 环内追问幂等 + 交付层表单（ActExecutor ask_user）
          .slot("pending_ask_slots", SlotType.STRING);

        Branch askUser = Branch.when(OpsDiagnoseWorkflowFactory.COLLECT_SLOTS_NODE,
                "slots.missing_count > 0");
        Branch ready = Branch.otherwise(OpsDiagnoseWorkflowFactory.INV_THINK_NODE);

        workflowBuilder.node(CustomNodeDefinition.of(OpsDiagnoseWorkflowFactory.EXTRACT_SLOTS_NODE,
                        OpsDiagnoseWorkflowFactory.SLOT_EXTRACT_EXECUTOR, "slot_extract_raw"))
                .node(ConditionNodeDefinition.of(OpsDiagnoseWorkflowFactory.SLOTS_GATE_NODE,
                        Branch.when(OpsDiagnoseWorkflowFactory.AUTO_RESOLVE_NODE, "slots.missing_count > 0"),
                        ready))
                .node(CustomNodeDefinition.of(OpsDiagnoseWorkflowFactory.AUTO_RESOLVE_NODE,
                        OpsDiagnoseWorkflowFactory.AUTO_RESOLVE_EXECUTOR, "auto_resolve_note"))
                .node(ConditionNodeDefinition.of(OpsDiagnoseWorkflowFactory.AUTO_GATE_NODE, askUser, ready))
                .node(CustomNodeDefinition.of(OpsDiagnoseWorkflowFactory.COLLECT_SLOTS_NODE,
                        OpsDiagnoseWorkflowFactory.ASK_MISSING_EXECUTOR, "clarify_question"))
                // 复检挂在问齐环迭代守卫下：超过补问上限即放行
                .node(ConditionNodeDefinition.of(OpsDiagnoseWorkflowFactory.SLOTS_REGATE_NODE,
                        NodeMeta.empty().withGuards(OpsDiagnoseWorkflowFactory.INTAKE_ITERATION_GUARD),
                        askUser, ready))

                .edge(OpsDiagnoseWorkflowFactory.EXTRACT_SLOTS_NODE, OpsDiagnoseWorkflowFactory.SLOTS_GATE_NODE)
                .edge(OpsDiagnoseWorkflowFactory.SLOTS_GATE_NODE, OpsDiagnoseWorkflowFactory.AUTO_RESOLVE_NODE)
                .edge(OpsDiagnoseWorkflowFactory.SLOTS_GATE_NODE, OpsDiagnoseWorkflowFactory.INV_THINK_NODE)
                .edge(OpsDiagnoseWorkflowFactory.AUTO_RESOLVE_NODE, OpsDiagnoseWorkflowFactory.AUTO_GATE_NODE)
                .edge(OpsDiagnoseWorkflowFactory.AUTO_GATE_NODE, OpsDiagnoseWorkflowFactory.COLLECT_SLOTS_NODE)
                .edge(OpsDiagnoseWorkflowFactory.AUTO_GATE_NODE, OpsDiagnoseWorkflowFactory.INV_THINK_NODE)
                .edge(OpsDiagnoseWorkflowFactory.COLLECT_SLOTS_NODE, OpsDiagnoseWorkflowFactory.SLOTS_REGATE_NODE)
                .edge(OpsDiagnoseWorkflowFactory.SLOTS_REGATE_NODE, OpsDiagnoseWorkflowFactory.COLLECT_SLOTS_NODE)
                .edge(OpsDiagnoseWorkflowFactory.SLOTS_REGATE_NODE, OpsDiagnoseWorkflowFactory.INV_THINK_NODE)

                .region(RegionDefinition.of(OpsDiagnoseWorkflowFactory.INTAKE_REGION, Paradigm.HUMAN_IN_LOOP,
                                OpsDiagnoseWorkflowFactory.EXTRACT_SLOTS_NODE,
                                OpsDiagnoseWorkflowFactory.SLOTS_GATE_NODE,
                                OpsDiagnoseWorkflowFactory.AUTO_RESOLVE_NODE,
                                OpsDiagnoseWorkflowFactory.AUTO_GATE_NODE,
                                OpsDiagnoseWorkflowFactory.COLLECT_SLOTS_NODE,
                                OpsDiagnoseWorkflowFactory.SLOTS_REGATE_NODE)
                        .withLoop(RegionLoop.of(OpsDiagnoseWorkflowFactory.COLLECT_SLOTS_NODE,
                                OpsDiagnoseWorkflowFactory.SLOTS_REGATE_NODE,
                                OpsDiagnoseWorkflowFactory.INTAKE_MAX_ROUNDS)
                                .withCounterSlot("intake_round")));
    }

    @Override
    public void wireRuntime(EngineBuilder eb, SharedDeps deps) {
        OpsSlotExtractor extractor = new OpsSlotExtractor(deps.model(), deps.mapper(), deps.promptBody());
        eb.nodeExecutor(OpsDiagnoseWorkflowFactory.SLOT_EXTRACT_EXECUTOR,
                        new SlotExtractExecutor(extractor, deps.clock()))
                // 自主补全：推断模型复用抽槽同源（不可用时只走规则与反查）
                .nodeExecutor(OpsDiagnoseWorkflowFactory.AUTO_RESOLVE_EXECUTOR,
                        new AutoResolveExecutor(deps.model(), deps.toolExecutor(), deps.mapper(),
                                deps.clock(), deps.promptBody()))
                .nodeExecutor(OpsDiagnoseWorkflowFactory.ASK_MISSING_EXECUTOR,
                        new AskMissingExecutor(extractor))
                .loopGuard(OpsDiagnoseWorkflowFactory.INTAKE_ITERATION_GUARD,
                        OpsDiagnoseWorkflowFactory.INTAKE_MAX_ROUNDS);
    }
}
