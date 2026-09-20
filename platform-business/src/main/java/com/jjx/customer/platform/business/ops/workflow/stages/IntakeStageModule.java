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
import com.jjx.customer.platform.business.ops.node.ConfirmExecutor;
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
 * extract_slots → auto_resolve（规则 → 日志反查 → 推断）→ auto_gate ─┬─（齐备）→ confirm_slots → 阶段 1
 *                                                                     └─（仍缺）→ collect_slots ⇄ slots_regate（问齐环，3 轮有界）
 * </pre>
 *
 * <p><b>2026-09-19 顺序修正</b>：删掉了原来的 slots_gate（"缺必填才补全"的门）。
 * 日志反查是<b>一手信息源</b>而不是缺槽时的自救——只要用户给了基础信息（trace_id 或 关键字+时间窗）
 * 就先查一遍日志，用日志里的接口/请求/响应/报错把槽位补起来，再谈还缺什么。</p>
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
                OpsDiagnoseWorkflowFactory.ASK_MISSING_EXECUTOR,
                OpsDiagnoseWorkflowFactory.CONFIRM_EXECUTOR);
    }

    @Override
    public void declareGraph(WorkflowBuilder workflowBuilder) {
        // 槽位：业务七槽（全流程输入契约）+ 问齐环控制槽 + 挂起恢复通道族
        OpsSlotCatalog.declareBusinessSlots(workflowBuilder);
        workflowBuilder.slot("missing_count", SlotType.NUMBER)   // 门禁判定
          .slot("inferred_slots", SlotType.STRING)  // 自主补全溯源
          .slot("user_clarify", SlotType.STRING)    // 问齐挂起恢复：Input.slots 回灌
          .slot("pending_ask", SlotType.STRING)     // 环内追问幂等 + 交付层表单（ActExecutor ask_user）
          .slot("pending_ask_slots", SlotType.STRING)
          // 上一轮已确立的诊断主张：追问轮由 OpsRunner 从 sa_agent_finding 装入。
          // 没有它，"你这结论不对"会让模型不知道上一轮断言了什么，只能从零重排。
          .slot("prior_findings", SlotType.STRING)
          // 关联诊断背景（P1.5）：同会话早前任务的结论概要，由 OpsRunner 装入——
          // 让新任务里「刚才那个问题」可见。与 prior_findings 分槽：编号语义不同
          //（本任务主张可被否定指认 vs 跨任务背景不可指认）
          .slot("related_findings", SlotType.STRING)
          // 基本信息已确认过（重跑轮由 OpsRunner 预填 1）：同一 Task 的追问轮不再重复确认——
          // 确认门摊开的是「被推断的前提」，前提没变就不该再问（plan/2026-09-20-task-qa-loop.md §2.4）
          .slot("intake_confirmed", SlotType.NUMBER);

        // 重跑轮直通：已确认过且齐备 → 跳过确认门直进阶段 1（分支按声明顺序首中即出）。
        // 判已确认必须用 == 1 而不是 > 0：槽位未设置时值为 null，数值比较会退化成
        // 字符串比较（"null" > "0" 为真），全新会话会被误判成"已确认过"绕过确认门
        Branch skipConfirm = Branch.when(OpsDiagnoseWorkflowFactory.INV_THINK_NODE,
                "slots.intake_confirmed == 1 && slots.missing_count == 0");
        Branch askUser = Branch.when(OpsDiagnoseWorkflowFactory.COLLECT_SLOTS_NODE,
                "slots.missing_count > 0");
        // 齐备后统一走确认门（人在环中）：不直接开诊断，先让用户确认这些信息
        Branch ready = Branch.otherwise(OpsDiagnoseWorkflowFactory.CONFIRM_SLOTS_NODE);

        // 抽槽 → 无条件进自主补全（规则 → 日志反查 → 推断）：缺不缺都由 auto_gate 判，
        // 不再有"缺必填才补全"的前置门——日志反查必须在问用户之前跑
        workflowBuilder.node(CustomNodeDefinition.of(OpsDiagnoseWorkflowFactory.EXTRACT_SLOTS_NODE,
                        OpsDiagnoseWorkflowFactory.SLOT_EXTRACT_EXECUTOR, "slot_extract_raw"))
                .node(CustomNodeDefinition.of(OpsDiagnoseWorkflowFactory.AUTO_RESOLVE_NODE,
                        OpsDiagnoseWorkflowFactory.AUTO_RESOLVE_EXECUTOR, "auto_resolve_note"))
                .node(ConditionNodeDefinition.of(OpsDiagnoseWorkflowFactory.AUTO_GATE_NODE,
                        skipConfirm, askUser, ready))
                .node(CustomNodeDefinition.of(OpsDiagnoseWorkflowFactory.COLLECT_SLOTS_NODE,
                        OpsDiagnoseWorkflowFactory.ASK_MISSING_EXECUTOR, "clarify_question"))
                .node(CustomNodeDefinition.of(OpsDiagnoseWorkflowFactory.CONFIRM_SLOTS_NODE,
                        OpsDiagnoseWorkflowFactory.CONFIRM_EXECUTOR, "pending_human_request"))
                // 复检挂在问齐环迭代守卫下：超过补问上限即放行
                .node(ConditionNodeDefinition.of(OpsDiagnoseWorkflowFactory.SLOTS_REGATE_NODE,
                        NodeMeta.empty().withGuards(OpsDiagnoseWorkflowFactory.INTAKE_ITERATION_GUARD),
                        skipConfirm, askUser, ready))

                .edge(OpsDiagnoseWorkflowFactory.EXTRACT_SLOTS_NODE,
                        OpsDiagnoseWorkflowFactory.AUTO_RESOLVE_NODE)
                .edge(OpsDiagnoseWorkflowFactory.AUTO_RESOLVE_NODE, OpsDiagnoseWorkflowFactory.AUTO_GATE_NODE)
                .edge(OpsDiagnoseWorkflowFactory.AUTO_GATE_NODE, OpsDiagnoseWorkflowFactory.COLLECT_SLOTS_NODE)
                .edge(OpsDiagnoseWorkflowFactory.AUTO_GATE_NODE, OpsDiagnoseWorkflowFactory.CONFIRM_SLOTS_NODE)
                // 重跑轮直通的出口边（条件分支目标必须是已声明出边）
                .edge(OpsDiagnoseWorkflowFactory.AUTO_GATE_NODE, OpsDiagnoseWorkflowFactory.INV_THINK_NODE)
                .edge(OpsDiagnoseWorkflowFactory.COLLECT_SLOTS_NODE, OpsDiagnoseWorkflowFactory.SLOTS_REGATE_NODE)
                .edge(OpsDiagnoseWorkflowFactory.SLOTS_REGATE_NODE, OpsDiagnoseWorkflowFactory.COLLECT_SLOTS_NODE)
                .edge(OpsDiagnoseWorkflowFactory.SLOTS_REGATE_NODE, OpsDiagnoseWorkflowFactory.CONFIRM_SLOTS_NODE)
                // 确认门 → 阶段 1（确认后走动态边；边先声明出来，动态白名单要求目标是出边）
                .edge(OpsDiagnoseWorkflowFactory.CONFIRM_SLOTS_NODE, OpsDiagnoseWorkflowFactory.INV_THINK_NODE)
                .dynamic(OpsDiagnoseWorkflowFactory.CONFIRM_SLOTS_NODE, OpsDiagnoseWorkflowFactory.INV_THINK_NODE)

                .region(RegionDefinition.of(OpsDiagnoseWorkflowFactory.INTAKE_REGION, Paradigm.HUMAN_IN_LOOP,
                                OpsDiagnoseWorkflowFactory.EXTRACT_SLOTS_NODE,
                                OpsDiagnoseWorkflowFactory.AUTO_RESOLVE_NODE,
                                OpsDiagnoseWorkflowFactory.AUTO_GATE_NODE,
                                OpsDiagnoseWorkflowFactory.COLLECT_SLOTS_NODE,
                                OpsDiagnoseWorkflowFactory.SLOTS_REGATE_NODE,
                                OpsDiagnoseWorkflowFactory.CONFIRM_SLOTS_NODE)
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
                        new AskMissingExecutor(extractor, deps.humanResponseInterpreter()))
                .nodeExecutor(OpsDiagnoseWorkflowFactory.CONFIRM_EXECUTOR,
                        new ConfirmExecutor(deps.humanResponseInterpreter()))
                .loopGuard(OpsDiagnoseWorkflowFactory.INTAKE_ITERATION_GUARD,
                        OpsDiagnoseWorkflowFactory.INTAKE_MAX_ROUNDS);
    }
}
