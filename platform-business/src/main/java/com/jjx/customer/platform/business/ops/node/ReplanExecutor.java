package com.jjx.customer.platform.business.ops.node;

import com.jjx.customer.platform.business.engine.adapter.SingleTurnModel;

import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.node.NodeType;
import com.agentframework.definition.workflow.HumanRequest;
import com.agentframework.definition.workflow.HumanResponse;
import com.agentframework.engine.core.NodeContext;
import com.agentframework.engine.core.NodeResult;
import com.agentframework.engine.workflowruntime.NodeExecutor;
import com.jjx.customer.platform.business.ops.AutonomyLevel;
import com.jjx.customer.platform.business.ops.HumanResponseInterpreter;
import com.jjx.customer.platform.business.ops.workflow.OpsDiagnoseWorkflowFactory;
import com.jjx.customer.platform.business.ops.slot.OpsSlotCatalog;
import com.jjx.customer.platform.business.ops.slot.OpsSlotExtractor;
import com.jjx.customer.platform.business.ops.slot.SlotProvenance;
import com.jjx.customer.platform.business.workflow.common.ActExecutor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * replan 四态裁决执行器（O6）：阶段刚结束时评估产出质量，
 * continue / adjust / ask_human / escalate。
 *
 * <p>对齐参考实现的裁决语义并落实两处修正：
 * D7——adjust 重试计数改为**每阶段一个**（参考是流程级且不复位，阶段 1 用过阶段 2 就直接超限）；
 * D8——adjust 的重跑只携带本轮产出的上下文（经 replan_note 注入下一轮阶段 prompt）。
 * 解析失败或模型不可用按 continue（参考语义：不阻断）。</p>
 *
 * <p>D12——**有证据就不移交**：阶段产出有工具返回支撑（scratchpad 里有 Observation）时，
 * 模型的 escalate 裁决降级为 continue。</p>
 *
 * <p><b>人在环中（2026-09-19）</b>：ask_human 与 escalate（含 adjust 额度耗尽）不再是终态，
 * 而是产出 {@link HumanRequest}（DECIDE）挂起——决策权移交用户，附证据摘要与点选项
 * （继续/终止），用户回复后流程从本节点重入继续。终止从默认结局变成选项之一；
 * 用户主动给的重跑指令（directive）不占 adjust 重试额度（那是防模型自循环的，
 * 防用户循环由流程级 llm_calls 上限兜底）。挂起暂存走 {@code pending_human_request}
 * 槽位（与 pending_ask 模式同构），恢复经 {@link HumanResponse#parse} 消费。</p>
 */
public class ReplanExecutor implements NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(ReplanExecutor.class);

    /** 每阶段 adjust 重试上限（对齐参考 maxAdjustRetries=1，但按阶段独立计数）。 */
    public static final int MAX_ADJUST_RETRIES = 1;

    private final String stagePrefix;

    private final String stageLabel;

    private final String nextStageLabel;

    private final String adjustTarget;

    private final String continueTarget;

    private final SingleTurnModel model;

    private final ObjectMapper objectMapper;

    /** replan 裁决正文来源（绑定包覆盖优先，classpath 兜底；null = 确定性降级路径） */
    private final java.util.function.Function<String, String> promptBody;

    /** 用户回复解释器（人在环中 P2；null = 退化到 {@link HumanResponse#parse} 的前缀解析） */
    private final HumanResponseInterpreter interpreter;

    /**
     * @param stagePrefix   刚结束阶段的槽位前缀（inv/res）
     * @param stageLabel    刚结束阶段名（裁决 user 消息展示）
     * @param nextStageLabel 下一阶段名（裁决 user 消息展示）
     * @param adjustTarget  adjust 时的重跑目标节点（本阶段 think）
     * @param continueTarget continue 时的下一阶段节点
     * @param model         裁决模型（null 不可用时按 continue）
     * @param objectMapper  JSON 解析
     * @param promptBody    prompt key → 正文（读 workflow/ops_diagnose_v2/replan）
     * @param interpreter   决策挂起重入时的回复解释器（null = 只认 #decision: 前缀）
     */
    public ReplanExecutor(String stagePrefix, String stageLabel, String nextStageLabel,
            String adjustTarget, String continueTarget, SingleTurnModel model,
            ObjectMapper objectMapper, java.util.function.Function<String, String> promptBody,
            HumanResponseInterpreter interpreter) {
        this.stagePrefix = stagePrefix;
        this.stageLabel = stageLabel;
        this.nextStageLabel = nextStageLabel;
        this.adjustTarget = adjustTarget;
        this.continueTarget = continueTarget;
        this.model = model;
        this.objectMapper = objectMapper;
        this.promptBody = promptBody;
        this.interpreter = interpreter;
    }

    @Override
    public NodeType type() {
        return NodeType.CUSTOM;
    }

    @Override
    public NodeResult execute(NodeDefinition node, NodeContext context) {
        // ① 重入消费：上一轮 DECIDE 挂起后带用户决议回来 → redirect（重跑本阶段）或 terminate（终态）
        String pending = context.slots().getString(HumanRequest.PENDING_SLOT, "");
        if (!pending.isBlank()) {
            return resumeFromDecision(node, context, pending);
        }

        String stageOutput = context.slots().getString(stagePrefix + "_stage_output", "");
        Map<String, Object> writes = new LinkedHashMap<>();
        Verdict verdict = judge(context, stageOutput);

        int retries = retriesOf(context);
        // ask_human / escalate / 额度耗尽统一收敛为 DECIDE 挂起（非 null = 本次裁决产出决策移交）
        HumanRequest handoff = null;
        String target;
        switch (verdict.action()) {
            case "continue" -> {
                writes.put("replan_verdict", "continue");
                writes.put("replan_note", "");
                target = continueTarget;
            }
            case "adjust" -> {
                if (retries >= MAX_ADJUST_RETRIES) {
                    // D7：本阶段重试额度用尽 → 决策移交（用户可能给得出模型给不出的新方向）
                    writes.put("replan_verdict", "ask_human");
                    handoff = decideRequest("阶段 " + stageLabel + " 自动重试额度已用尽",
                            "自动重试 " + MAX_ADJUST_RETRIES + " 次仍未得到可用结论：" + verdict.reason(),
                            verdict.hypotheses());
                    target = null;
                } else {
                    writes.put("replan_verdict", "adjust");
                    writes.put("replan_note", verdict.adjustment().isBlank()
                            ? verdict.reason() : verdict.adjustment());
                    writes.put(stagePrefix + "_retries", retries + 1);
                    // D8：清掉本阶段上一轮产出与过程记录，重跑只带修正要求，不叠加失败产物
                    writes.put(stagePrefix + "_stage_output", "");
                    writes.put(stagePrefix + "_scratchpad", "");
                    target = adjustTarget;
                }
            }
            case "ask_human" -> {
                writes.put("replan_verdict", "ask_human");
                handoff = decideRequest(
                        verdict.question().isBlank() ? "排查需要你的判断" : verdict.question(),
                        verdict.evidence().isBlank() ? verdict.reason() : verdict.evidence(),
                        verdict.hypotheses());
                target = null;
            }
            case "escalate" -> {
                if (hasToolBackedConclusion(context, stageOutput)) {
                    // D12：阶段已经给出有工具返回支撑的结论 → 按骨架继续，结论由收尾节点直出
                    log.info("[ops-replan] 阶段 {} 已有工具支撑的结论，escalate 降级为 continue（原因为：{}）",
                            stageLabel, verdict.reason());
                    writes.put("replan_verdict", "continue");
                    writes.put("replan_note", "");
                    target = continueTarget;
                } else {
                    // escalate 不再是死亡终点：终局判断交给用户（终止是选项之一）
                    writes.put("replan_verdict", "ask_human");
                    handoff = decideRequest("排查在这里卡住了，需要你的决策",
                            verdict.reason().isBlank() ? "模型判断无法继续" : verdict.reason(),
                            verdict.hypotheses());
                    target = null;
                }
            }
            default -> {
                writes.put("replan_verdict", "continue");
                writes.put("replan_note", "");
                target = continueTarget;
            }
        }

        // ② 决策移交：挂起 + 请求暂存（交付侧从槽位读出结构化请求渲染证据与选项）
        if (handoff != null) {
            String askText = handoff.prompt();
            NodeResult suspended = NodeResult.suspended(node.id(), askText);
            for (Map.Entry<String, Object> entry : writes.entrySet()) {
                suspended = suspended.withSlotWrite(entry.getKey(), entry.getValue());
            }
            suspended = suspended.withSlotWrite(HumanRequest.PENDING_SLOT, writeRequest(handoff));
            if (model != null) {
                suspended = suspended.withSlotWrite("llm_calls", llmCalls(context) + 1);
            }
            log.info("[ops-replan] 阶段 {} 决策移交（{}）：{}", stageLabel, verdict.action(), askText);
            return suspended;
        }

        // 动态结果同样要带回全部槽位写入（D8 的清理与 replan_note 都在这里落槽位）
        NodeResult result = NodeResult.dynamic(node.id(), writes.get("replan_verdict").toString(), target);
        for (Map.Entry<String, Object> entry : writes.entrySet()) {
            result = result.withSlotWrite(entry.getKey(), entry.getValue());
        }
        // O9 计数口径：裁决也是一次 LLM 调用
        if (model != null) {
            result = result.withSlotWrite("llm_calls", llmCalls(context) + 1);
        }
        return result;
    }

    /**
     * DECIDE 挂起重入：消费用户决议（P2 起由 {@link HumanResponseInterpreter} 解析，
     * 点选回传与模型不可用时退化到 {@link HumanResponse#parse}——最坏等于 P1 行为）。
     *
     * <p>三条出路：</p>
     * <ul>
     *   <li><b>terminate</b> → 升级终态（用户确认结束，终止是选项之一而非默认）；</li>
     *   <li><b>带新信息</b>（槽值补充 / 推翻推断 / 方向指令）→ redirect：槽值当即落槽，
     *       指令进 {@code user_directive}（soft 通道，跨阶段可见），replan_note 只留一句
     *       「为什么重跑」的指针，重跑本阶段（同 D8 清理）。人给的指示不占 adjust 重试额度
     *       （那是防模型自循环的，防用户循环由 llm_calls 上限兜底）；</li>
     *   <li><b>没带来新信息</b>（点了「继续」却没补充 / 只回了句应承）→ 原请求重挂再问一轮：
     *       空转重跑一遍阶段纯属浪费，每轮都要用户回复所以不会自循环。</li>
     * </ul>
     */
    private NodeResult resumeFromDecision(NodeDefinition node, NodeContext context, String pendingJson) {
        String reply = replyOf(context);
        HumanRequest pending = readRequest(pendingJson);
        Map<String, String> before = businessSlots(context);
        HumanResponseInterpreter.Outcome outcome = interpreter == null
                ? null : interpreter.interpret(reply, pending, before);
        HumanResponse response = outcome == null ? HumanResponse.parse(reply) : outcome.response();

        Map<String, Object> writes = new LinkedHashMap<>();
        writes.put(HumanRequest.PENDING_SLOT, "");  // 暂存一次性消费，防止空回复重入死循环
        writes.putAll(response.mergedFills());      // 补缺 + 推翻推断：用户纠错当即落槽
        if (!response.directive().isBlank()) {
            writes.put(ActExecutor.USER_DIRECTIVE_SLOT, response.directive());
        }
        if (response.autonomyHint() != null) {
            writes.put(HumanResponseInterpreter.AUTONOMY_HINT_SLOT, response.autonomyHint());
            // 顺带调档（P3）：档位槽即刻生效并随会话持久化
            AutonomyLevel hintLevel = AutonomyLevel.fromHint(response.autonomyHint());
            if (hintLevel != null) {
                writes.put(AutonomyLevel.SLOT, hintLevel.name());
            }
        }
        if (outcome != null && outcome.llmCalled()) {
            writes.put("llm_calls", llmCalls(context) + 1);
        }
        // 推翻的 provenance 状态机（P3）：改写了原有机器取值 → 转 user_override
        writes.putAll(overriddenProvenance(response.mergedFills(), before, context));

        if ("terminate".equals(response.decision())) {
            writes.put("replan_verdict", "escalate");
            writes.put("escalate_reason", "用户确认终止排查"
                    + (response.directive().isBlank() ? "" : "：" + response.directive()));
            NodeResult terminal = NodeResult.dynamic(node.id(), "escalate",
                    OpsDiagnoseWorkflowFactory.ESCALATE_NODE);
            for (Map.Entry<String, Object> entry : writes.entrySet()) {
                terminal = terminal.withSlotWrite(entry.getKey(), entry.getValue());
            }
            log.info("[ops-replan] 用户确认终止（阶段 {}）", stageLabel);
            return terminal;
        }

        // 空回复重挂：没有新信息就不空跑一整轮阶段（挂起请求原样再问一次）
        boolean noNewInfo = response.mergedFills().isEmpty() && response.directive().isBlank();
        if (noNewInfo && pending != null) {
            return reask(node, pending, writes);
        }

        // redirect：重跑本阶段（同 D8：清上一轮产物，只带新信息与用户指示）
        writes.put("replan_verdict", "redirect");
        writes.put("replan_note", redirectNote(response));
        writes.put(stagePrefix + "_stage_output", "");
        writes.put(stagePrefix + "_scratchpad", "");
        NodeResult redirect = NodeResult.dynamic(node.id(), "redirect", adjustTarget);
        for (Map.Entry<String, Object> entry : writes.entrySet()) {
            redirect = redirect.withSlotWrite(entry.getKey(), entry.getValue());
        }
        log.info("[ops-replan] 决策移交回续（阶段 {}）：槽位 {} 项，指令「{}」", stageLabel,
                response.mergedFills().size(), preview(response.directive()));
        return redirect;
    }

    /**
     * 重跑提示（进 think prompt 的「修正要求」段）：只写「这一轮为什么重跑」的指针，
     * 用户指令原文归 {@code user_directive}（同一份内容出现两次会让模型误判权重）。
     */
    private static String redirectNote(HumanResponse response) {
        if (!response.directive().isBlank() && !response.mergedFills().isEmpty()) {
            return "用户补充了信息并给出了指示（见「用户补充说明」），按新线索重跑本阶段";
        }
        if (!response.directive().isBlank()) {
            return "用户给出了新指示（见「用户补充说明」），按其调整后重跑本阶段";
        }
        return "用户补充/纠正了槽位信息，按更新后的信息重跑本阶段";
    }

    /**
     * 空回复重挂：同一决策请求再问一轮（正文补一句提示，避免用户以为点了按钮就能推进）。
     *
     * @param writes 已备好的槽位写入（决议解析产物照常落槽，只有路由不变）
     */
    private NodeResult reask(NodeDefinition node, HumanRequest pending, Map<String, Object> writes) {
        String prompt = pending.prompt().isBlank() ? "排查需要你的判断" : pending.prompt();
        String nudge = prompt + "\n（本次没有收到新信息：补充 traceId / 时间 / 报文，或直接说明你的怀疑方向）";
        HumanRequest again = new HumanRequest(pending.kind(), nudge, pending.slots(),
                pending.context(), pending.options(), pending.allowFreeText());
        writes.put("replan_verdict", "ask_human");
        NodeResult suspended = NodeResult.suspended(node.id(), nudge);
        for (Map.Entry<String, Object> entry : writes.entrySet()) {
            suspended = suspended.withSlotWrite(entry.getKey(), entry.getValue());
        }
        suspended = suspended.withSlotWrite(HumanRequest.PENDING_SLOT, writeRequest(again));
        log.info("[ops-replan] 空回复重挂（阶段 {}）：未收到新信息，原请求再问一轮", stageLabel);
        return suspended;
    }

    /** 暂存的决策请求（解析失败按 null：解释器退化为无提问上下文的纯文本解析）。 */
    private HumanRequest readRequest(String json) {
        try {
            return objectMapper.readValue(json, HumanRequest.class);
        } catch (Exception e) {
            log.warn("[ops-replan] 决策请求反序列化失败（按纯文本解析回复）：{}", e.getMessage());
            return null;
        }
    }

    /** 当前业务槽值（目录序；解释器判定「补空」还是「推翻已有值」的依据）。 */
    private static Map<String, String> businessSlots(NodeContext context) {
        Map<String, String> slots = new LinkedHashMap<>();
        for (OpsSlotCatalog.Spec spec : OpsSlotCatalog.ALL) {
            String value = context.slots().getString(spec.name(), "");
            if (!value.isBlank()) {
                slots.put(spec.name(), value);
            }
        }
        return slots;
    }

    /**
     * 推翻的 provenance 状态机（P3）：用户改写的机器取值转 {@code user_override}
     * （结论证据链与前端角标同源）。
     *
     * @param applied 本轮落槽的键值
     * @param before  落槽前的槽值快照
     * @param context 节点上下文（读现有 inferred_slots）
     * @return 待落槽的 provenance 写入（无改写时为空）
     */
    private Map<String, Object> overriddenProvenance(Map<String, String> applied,
            Map<String, String> before, NodeContext context) {
        Map<String, String> overridden = new LinkedHashMap<>();
        applied.forEach((name, value) -> {
            String existing = before.get(name);
            if (existing != null && !existing.isBlank()) {
                overridden.put(name, value);
            }
        });
        if (overridden.isEmpty()) {
            return Map.of();
        }
        String updated = SlotProvenance.upsertAll(
                context.slots().getString(AutoResolveExecutor.INFERRED_SLOTS_SLOT, ""),
                overridden, SlotProvenance.SOURCE_OVERRIDDEN, "用户更正");
        return Map.of(AutoResolveExecutor.INFERRED_SLOTS_SLOT, updated);
    }

    private static String preview(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ");
        return oneLine.length() <= 80 ? oneLine : oneLine.substring(0, 80) + "…";
    }

    private int llmCalls(NodeContext context) {
        Object value = context.slots().get("llm_calls");
        return value instanceof Number number ? number.intValue() : 0;
    }

    /**
     * 决策移交请求：证据上下文 + 竞争假设（结构化决策面）+ 继续优先的选项。
     *
     * <p>hypotheses 为空时卡片退化为现状纯文本（不劣化）；非空时用户看到的是
     * 「每个假设的验证状态 + 判别动作」的完整假设空间。</p>
     */
    private HumanRequest decideRequest(String question, String evidence,
            List<HumanRequest.Hypothesis> hypotheses) {
        List<String> evidencePoints = evidence == null || evidence.isBlank()
                ? List.of() : List.of(evidence.split("\\n+"));
        return HumanRequest.decide(question,
                new HumanRequest.DecisionContext("阶段「" + stageLabel + "」结束后需要人工判断",
                        evidencePoints, hypotheses == null ? List.of() : hypotheses),
                List.of(
                        new HumanRequest.Choice("redirect", "我补充信息，继续排查",
                                "回复具体信息（traceId / 时间 / 报文 / 你的怀疑方向），将按新线索重跑本阶段"),
                        new HumanRequest.Choice("terminate", "终止排查",
                                "结束本次诊断，保留已查到的结论与证据")));
    }

    /** HumanRequest 序列化暂存（失败兜底为空串 = 挂起仍成立，交付侧按纯文本渲染）。 */
    private String writeRequest(HumanRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            log.warn("[ops-replan] 决策请求序列化失败（按纯文本挂起）: {}", e.getMessage());
            return "";
        }
    }

    /** 用户回复：优先恢复输入写入的 user_clarify 槽位，其次本轮文本（与 AskMissing 同口径）。 */
    private String replyOf(NodeContext context) {
        String clarified = context.slots().getString(ActExecutor.USER_CLARIFY_SLOT, "");
        if (!clarified.isBlank()) {
            return clarified;
        }
        return context.input() == null ? "" : context.input().text();
    }

    /**
     * 调用裁决模型并解析三态。
     *
     * @param context     上下文
     * @param stageOutput 刚结束阶段的产出
     * @return 裁决（任何失败都退化为 continue）
     */
    private Verdict judge(NodeContext context, String stageOutput) {
        if (model == null) {
            return Verdict.continueOf("模型不可用，按骨架继续");
        }
        String system = promptBody.apply("workflow/ops_diagnose_v2/replan")
                + "\n\n## 阶段序列\n" + stageLabel + " →（刚结束）\n" + nextStageLabel + " →（下一个）";
        String user = "上一阶段（" + stageLabel + "）产出：\n"
                + (stageOutput.isBlank() ? "（无文本产出）" : stageOutput);
        try {
            Map<String, Object> parsed = parse(model.ask(system, user));
            if (parsed == null) {
                return Verdict.continueOf("裁决输出无法解析，按骨架继续");
            }
            String action = normalizeAction(firstOf(parsed, "action", "decision"));
            String reason = String.valueOf(firstOf(parsed, "reason", "reason ") == null ? "" : firstOf(parsed, "reason"));
            String adjustment = parsed.get("adjustment") == null || "null".equals(parsed.get("adjustment"))
                    ? "" : String.valueOf(parsed.get("adjustment"));
            String question = parsed.get("question") == null || "null".equals(parsed.get("question"))
                    ? "" : String.valueOf(parsed.get("question"));
            String evidence = parsed.get("evidence") == null || "null".equals(parsed.get("evidence"))
                    ? "" : String.valueOf(parsed.get("evidence"));
            if (action.isEmpty()) {
                return Verdict.continueOf("裁决缺少 action，按骨架继续");
            }
            return new Verdict(action, reason, adjustment, question, evidence,
                    hypothesesOf(parsed.get("hypotheses")));
        } catch (Exception e) {
            log.warn("[ops-replan] 裁决失败（按 continue）：{}", e.getMessage());
            return Verdict.continueOf("裁决失败：" + e.getMessage());
        }
    }

    private Map<String, Object> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        int braceStart = text.indexOf('{');
        int braceEnd = text.lastIndexOf('}');
        if (braceStart < 0 || braceEnd <= braceStart) {
            return null;
        }
        try {
            return objectMapper.readValue(text.substring(braceStart, braceEnd + 1),
                    new TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalizeAction(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value).trim().toLowerCase();
    }

    private static Object firstOf(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private int retriesOf(NodeContext context) {
        Object value = context.slots().get(stagePrefix + "_retries");
        return value instanceof Number number ? number.intValue() : 0;
    }

    /**
     * 阶段是否已产出「有工具返回支撑」的结论：既有 answer 文本，又执行过工具。
     *
     * <p>判据取 REPLAN 标准里 continue 的前半句（工具返回支撑），用来兜住模型的过度升级。
     * 只认 scratchpad 里的 {@code Observation:}（AskUser / OutputGate 记录不算证据）。</p>
     *
     * @param context     节点上下文
     * @param stageOutput 刚结束阶段的产出文本
     * @return true = 有工具支撑的结论，escalate 应降级为 continue
     */
    private boolean hasToolBackedConclusion(NodeContext context, String stageOutput) {
        if (stageOutput == null || stageOutput.isBlank()) {
            return false;
        }
        String scratchpad = context.slots().getString(stagePrefix + "_scratchpad", "");
        return !scratchpad.isBlank() && scratchpad.contains("Observation:");
    }

    /**
     * 裁决四态。
     *
     * @param action     continue/adjust/ask_human/escalate
     * @param reason     一句话理由
     * @param adjustment 重跑提示（仅 adjust）
     * @param question   向用户的决策问句（仅 ask_human）
     * @param evidence   已查明事实与卡住点摘要（仅 ask_human，随请求透出给用户做决策依据）
     * @param hypotheses 竞争假设（仅 ask_human：结构化决策面，每个带验证状态与判别动作）
     */
    record Verdict(String action, String reason, String adjustment, String question, String evidence,
                   List<HumanRequest.Hypothesis> hypotheses) {

        static Verdict continueOf(String reason) {
            return new Verdict("continue", reason, "", "", "", List.of());
        }
    }

    /**
     * 解析裁决输出的竞争假设（ask_human 的结构化决策面，2026-09-20 P0）。
     *
     * <p>无判别动作（{@code next_action}）的假设一律丢弃——不可证伪的假设对决策没有增量，
     * 进卡片只会稀释真假设（判据见 replan.md「假设外化」节）。</p>
     */
    private static List<HumanRequest.Hypothesis> hypothesesOf(Object value) {
        if (!(value instanceof List<?> raw) || raw.isEmpty()) {
            return List.of();
        }
        List<HumanRequest.Hypothesis> hypotheses = new ArrayList<>();
        for (Object item : raw) {
            if (!(item instanceof Map<?, ?> declared)) {
                continue;
            }
            HumanRequest.Hypothesis h = new HumanRequest.Hypothesis(
                    textOf(declared.get("claim")),
                    textOf(declared.get("status")),
                    textOf(declared.get("evidence")),
                    textOf(firstNonNull(declared.get("next_action"), declared.get("nextAction"))));
            if (h.actionable()) {
                hypotheses.add(h);
            } else {
                log.info("[ops-replan] 丢弃无判别动作的假设：{}", preview(h.claim()));
            }
        }
        return List.copyOf(hypotheses);
    }

    /** map 取值转文本（null/缺失 → 空串，"null" 字面量同样按空处理——与上方裁决字段同口径）。 */
    private static String textOf(Object value) {
        if (value == null || "null".equals(String.valueOf(value))) {
            return "";
        }
        return String.valueOf(value).trim();
    }

    private static Object firstNonNull(Object a, Object b) {
        return a != null ? a : b;
    }
}
