package com.jjx.customer.platform.business.ops.node;

import com.jjx.customer.platform.business.engine.adapter.SingleTurnModel;

import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.node.NodeType;
import com.agentframework.engine.core.NodeContext;
import com.agentframework.engine.core.NodeResult;
import com.agentframework.engine.workflowruntime.NodeExecutor;
import com.jjx.customer.platform.business.ops.workflow.OpsDiagnoseWorkflowFactory;
import com.jjx.customer.platform.business.ops.OpsPrompts;
import com.jjx.customer.platform.business.ops.slot.OpsSlotExtractor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * replan 三态裁决执行器（O6）：阶段刚结束时评估产出质量，continue / adjust / escalate。
 *
 * <p>对齐参考实现的裁决语义并落实两处修正：
 * D7——adjust 重试计数改为**每阶段一个**（参考是流程级且不复位，阶段 1 用过阶段 2 就直接超限）；
 * D8——adjust 的重跑只携带本轮产出的上下文（经 replan_note 注入下一轮阶段 prompt）。
 * 解析失败或模型不可用按 continue（参考语义：不阻断）。</p>
 *
 * <p>D12——**有证据就不升级**：阶段产出有工具返回支撑（scratchpad 里有 Observation）时，
 * 模型的 escalate 裁决降级为 continue。否则用户拿到的只有一句"需人工介入"，
 * 而阶段里已经查到的证据反而被丢掉。</p>
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

    /**
     * @param stagePrefix   刚结束阶段的槽位前缀（inv/res）
     * @param stageLabel    刚结束阶段名（裁决 user 消息展示）
     * @param nextStageLabel 下一阶段名（裁决 user 消息展示）
     * @param adjustTarget  adjust 时的重跑目标节点（本阶段 think）
     * @param continueTarget continue 时的下一阶段节点
     * @param model         裁决模型（null 不可用时按 continue）
     * @param objectMapper  JSON 解析
     */
    public ReplanExecutor(String stagePrefix, String stageLabel, String nextStageLabel,
            String adjustTarget, String continueTarget, SingleTurnModel model,
            ObjectMapper objectMapper) {
        this.stagePrefix = stagePrefix;
        this.stageLabel = stageLabel;
        this.nextStageLabel = nextStageLabel;
        this.adjustTarget = adjustTarget;
        this.continueTarget = continueTarget;
        this.model = model;
        this.objectMapper = objectMapper;
    }

    @Override
    public NodeType type() {
        return NodeType.CUSTOM;
    }

    @Override
    public NodeResult execute(NodeDefinition node, NodeContext context) {
        String stageOutput = context.slots().getString(stagePrefix + "_stage_output", "");
        Map<String, Object> writes = new LinkedHashMap<>();
        Verdict verdict = judge(context, stageOutput);

        int retries = retriesOf(context);
        String target;
        switch (verdict.action()) {
            case "continue" -> {
                writes.put("replan_verdict", "continue");
                writes.put("replan_note", "");
                target = continueTarget;
            }
            case "adjust" -> {
                if (retries >= MAX_ADJUST_RETRIES) {
                    // D7：本阶段重试额度用尽，降级为升级而不是硬重跑
                    writes.put("replan_verdict", "escalate");
                    writes.put("escalate_reason", "阶段 " + stageLabel + " adjust 重试额度用尽：" + verdict.reason());
                    target = OpsDiagnoseWorkflowFactory.ESCALATE_NODE;
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
            case "escalate" -> {
                if (hasToolBackedConclusion(context, stageOutput)) {
                    // D12：阶段已经给出有工具返回支撑的结论 → 按骨架继续，结论由收尾节点直出
                    log.info("[ops-replan] 阶段 {} 已有工具支撑的结论，escalate 降级为 continue（原因为：{}）",
                            stageLabel, verdict.reason());
                    writes.put("replan_verdict", "continue");
                    writes.put("replan_note", "");
                    target = continueTarget;
                } else {
                    writes.put("replan_verdict", "escalate");
                    writes.put("escalate_reason",
                            verdict.reason().isBlank() ? "模型判断无法继续" : verdict.reason());
                    target = OpsDiagnoseWorkflowFactory.ESCALATE_NODE;
                }
            }
            default -> {
                writes.put("replan_verdict", "continue");
                writes.put("replan_note", "");
                target = continueTarget;
            }
        }
        // 动态结果同样要带回全部槽位写入（D8 的清理与 replan_note 都在这里落槽位）
        NodeResult result = NodeResult.dynamic(node.id(), writes.get("replan_verdict").toString(), target);
        for (Map.Entry<String, Object> entry : writes.entrySet()) {
            result = result.withSlotWrite(entry.getKey(), entry.getValue());
        }
        // O9 计数口径：裁决也是一次 LLM 调用
        if (model != null) {
            Object used = context.slots().get("llm_calls");
            result = result.withSlotWrite("llm_calls", (used instanceof Number n ? n.intValue() : 0) + 1);
        }
        return result;
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
        String system = OpsPrompts.REPLAN
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
            if (action.isEmpty()) {
                return Verdict.continueOf("裁决缺少 action，按骨架继续");
            }
            return new Verdict(action, reason, adjustment);
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
     * 裁决三态。
     *
     * @param action     continue/adjust/escalate
     * @param reason     一句话理由
     * @param adjustment 重跑提示（仅 adjust）
     */
    record Verdict(String action, String reason, String adjustment) {

        static Verdict continueOf(String reason) {
            return new Verdict("continue", reason, "");
        }
    }
}
