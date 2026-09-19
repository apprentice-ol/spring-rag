package com.jjx.customer.platform.business.ops.node;

import com.jjx.customer.platform.business.workflow.common.ActExecutor;

import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.node.NodeType;
import com.agentframework.definition.workflow.HumanRequest;
import com.agentframework.definition.workflow.HumanResponse;
import com.agentframework.engine.core.NodeContext;
import com.agentframework.engine.core.NodeResult;
import com.agentframework.engine.workflowruntime.NodeExecutor;
import com.agentframework.runtime.session.Message;
import com.jjx.customer.platform.business.ops.AutonomyLevel;
import com.jjx.customer.platform.business.ops.HumanResponseInterpreter;
import com.jjx.customer.platform.business.ops.slot.OpsSlotCatalog;
import com.jjx.customer.platform.business.ops.slot.OpsSlotExtractor;
import com.jjx.customer.platform.business.ops.slot.SlotProvenance;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一次问齐节点执行器（O1 核心）：必填缺失时把全部缺失项拼成一次提问并挂起整次运行；
 * 用户补充后经 {@code resume} 从本节点重入，把答复解析回槽位再判定——不足则再问，
 * 齐备则放行。挂起/恢复语义与内核 HumanNode 同构（cursor 停在本节点，恢复即重入）。
 *
 * <p>问齐文案与参考实现完全同构：目录序 × {@code join("\n")}，无编号。
 * 已知槽位不重复问（缺失判定只算未填项；抽取只填空缺）。</p>
 *
 * <p><b>人在环中（2026-09-19）</b>：挂起时同步产出 {@link HumanRequest}（CLARIFY）暂存
 * {@code pending_human_request} 槽位——缺失项带目录的问句/提示/候选值，已自动补全项
 * （auto-resolve 产物）进 context 供用户当场纠错；交付侧据此渲染结构化卡片，
 * 正文文本版仅作落库兜底（前端卡片接管展示，不双发）。</p>
 */
public class AskMissingExecutor implements NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(AskMissingExecutor.class);

    /** 问齐文案槽位名（非空即"问过一轮"，是重入判定的标记）。 */
    public static final String CLARIFY_QUESTION_SLOT = "clarify_question";

    /** 结构化请求序列化（Jackson 线程安全，静态复用）。 */
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();
    /** 用户补充槽位名（恢复输入经 Input.slots 写入）。 */
    public static final String USER_CLARIFY_SLOT = ActExecutor.USER_CLARIFY_SLOT;

    private final OpsSlotExtractor extractor;

    /** 用户回复解释器（人在环中 P2；null = 只走抽槽器，行为同 P1） */
    private final HumanResponseInterpreter interpreter;

    /**
     * @param extractor   槽位抽取器
     * @param interpreter 回复解释器（补缺 / 推翻自动补全 / 方向指令）
     */
    public AskMissingExecutor(OpsSlotExtractor extractor, HumanResponseInterpreter interpreter) {
        this.extractor = extractor;
        this.interpreter = interpreter;
    }

    @Override
    public NodeType type() {
        return NodeType.CUSTOM;
    }

    @Override
    public NodeResult execute(NodeDefinition node, NodeContext context) {
        Map<String, String> confirmed = SlotExtractExecutor.confirmedSlots(context);
        String priorAsk = context.slots().getString(CLARIFY_QUESTION_SLOT, "");
        String reply = replyOf(context);

        Map<String, Object> writes = new LinkedHashMap<>();
        // 重入且带答复：先解析补充（只填空缺），再重判缺口；首轮（未问过）直接问
        if (!priorAsk.isBlank() && !reply.isBlank()) {
            writes.put(USER_CLARIFY_SLOT, reply);
            // ① 语义通道（P2）：槽值补充 / 推翻自动补全的值 / 方向指令
            Interpretation interpretation = interpret(reply, context, confirmed);
            writes.putAll(interpretation.writes());
            // ② 兜底抽槽：解释器已经抽到槽就信它（同一份目录 + 同一个模型，再抽一次是重复付费）；
            //    它没抽到（模型不可用 / 判空）才回落到抽槽器——那条路径与 P1 完全一致
            if (!interpretation.filledSlots()) {
                Map<String, String> extracted = extractor.extract(reply, confirmed);
                writes.putAll(extracted);
                confirmed.putAll(extracted);
            }
        }

        List<String> missing = SlotExtractExecutor.missingRequired(confirmed, Map.of());
        writes.put(SlotExtractExecutor.MISSING_COUNT_SLOT, missing.size());
        if (!missing.isEmpty()) {
            String askText = String.join("\n", missing.stream().map(AskMissingExecutor::questionOf).toList());
            // 自主补全透明化：已自动推断的项随问句透出，用户一轮内既补缺又可纠错。
            // 取「本轮已生效」的来源台账（writes 优先）——override 刚翻新的值必须立刻反映，
            // 读 context.slots() 会拿到写入前的旧值（用户点了「改成 最近1小时」卡片却还显示旧窗口）
            String provenanceJson = effectiveProvenance(writes, context);
            String autoNote = renderNote(provenanceJson);
            if (!autoNote.isBlank()) {
                askText = askText + "\n\n" + autoNote + "\n（以上已自动补全，如有误请直接指出）";
            }
            writes.put(CLARIFY_QUESTION_SLOT, askText);
            // 挂起结果同样要带回全部槽位写入：答复解析出的值必须先落槽位，否则重入会再问一遍
            NodeResult suspended = NodeResult.suspended(node.id(), askText)
                    .withSlotWrite(HumanRequest.PENDING_SLOT, writeClarifyRequest(missing, provenanceJson));
            for (Map.Entry<String, Object> entry : writes.entrySet()) {
                suspended = suspended.withSlotWrite(entry.getKey(), entry.getValue());
            }
            return suspended;
        }
        // 齐备：消费掉挂起暂存（防残留槽位把上一轮请求带进后续出口）
        writes.put(HumanRequest.PENDING_SLOT, "");
        if (!reply.isBlank() && !priorAsk.isBlank()) {
            return NodeResult.completed(node.id(), "信息已齐备", writes)
                    .withMessage(Message.user(reply));
        }
        return NodeResult.completed(node.id(), "信息已齐备", writes);
    }

    /**
     * 语义通道（人在环中 P2）：用户自由文本回复 → 槽值（补缺 / 推翻自动补全的值）+ 方向指令 + 档位提示。
     *
     * <p>解析出的槽值先落 {@code confirmed}，调用方据此决定是否还需要兜底抽槽。</p>
     *
     * @param reply     用户回复原文
     * @param context   节点上下文（取暂存请求与 llm_calls）
     * @param confirmed 当前已确认槽位（就地更新：解释器结果并入）
     * @return 落槽产物（写槽 + 本次是否抽到了槽值）
     */
    private Interpretation interpret(String reply, NodeContext context, Map<String, String> confirmed) {
        Map<String, Object> writes = new LinkedHashMap<>();
        if (interpreter == null) {
            return new Interpretation(writes, false);
        }
        Map<String, String> before = Map.copyOf(confirmed);
        HumanResponseInterpreter.Outcome outcome =
                interpreter.interpret(reply, pendingRequest(context), before);
        HumanResponse response = outcome.response();
        Map<String, String> applied = response.mergedFills();
        if (!applied.isEmpty()) {
            writes.putAll(applied);
            confirmed.putAll(applied);
        }
        if (!response.directive().isBlank()) {
            writes.put(ActExecutor.USER_DIRECTIVE_SLOT, response.directive());
        }
        if (response.autonomyHint() != null) {
            writes.put(HumanResponseInterpreter.AUTONOMY_HINT_SLOT, response.autonomyHint());
            // 顺带调档（P3）：档位槽即刻生效（后续阶段与下一轮都按新档走），并随会话持久化
            AutonomyLevel hintLevel = AutonomyLevel.fromHint(response.autonomyHint());
            if (hintLevel != null) {
                writes.put(AutonomyLevel.SLOT, hintLevel.name());
            }
        }
        if (outcome.llmCalled()) {
            Object used = context.slots().get("llm_calls");
            writes.put("llm_calls", (used instanceof Number n ? n.intValue() : 0) + 1);
        }
        writes.putAll(overriddenProvenance(applied, before, context));
        return new Interpretation(writes, !applied.isEmpty());
    }

    /**
     * 推翻的 provenance 状态机（P3）：用户改写的机器取值转 {@code user_override}
     * （卡片角标随之从「模型推断」变「已按你的说明更正」，结论证据链同源）。
     *
     * <p>判定用「改写前有没有值」而不是 {@code response.overrides()}——确定性回传
     * （{@code #override:}）分不清补缺与颠覆，这里统一口径。</p>
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
        log.info("[ops-ask] 用户更正机器补全值：{}", overridden.keySet());
        return Map.of(AutoResolveExecutor.INFERRED_SLOTS_SLOT, updated);
    }

    /**
     * 一次回复解释的落槽产物。
     *
     * @param writes      待落槽位的写入（槽值 + user_directive + autonomy_hint + llm_calls 计数）
     * @param filledSlots 本次是否抽到了槽值（false = 兜底抽槽器仍须跑）
     */
    private record Interpretation(Map<String, Object> writes, boolean filledSlots) {
    }

    /** 上轮挂起时暂存的问齐请求（提供本轮问到过的动态槽名，供解释器白名单放行）。 */
    private HumanRequest pendingRequest(NodeContext context) {
        String json = context.slots().getString(HumanRequest.PENDING_SLOT, "");
        if (json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, HumanRequest.class);
        } catch (Exception e) {
            return null; // 反序列化失败按无提问上下文解释，不影响主流程
        }
    }

    /**
     * 构造问齐的结构化请求：缺失项带目录问句/提示/候选值（前端问题卡片 + 点选项）；
     * 机器已补全项（P3）带值 + 来源角标一并透出，用户可当场纠正
     * （点选回传 {@code #override:}，或直接说话由回复解释器识别为推翻）。
     *
     * @param provenanceJson 「本轮已生效」的来源台账（不是 context 里的旧值）
     */
    private String writeClarifyRequest(List<String> missing, String provenanceJson) {
        try {
            List<HumanRequest.SlotAsk> asks = new ArrayList<>(missing.size());
            for (String name : missing) {
                OpsSlotCatalog.Spec spec = specOf(name);
                asks.add(spec == null
                        ? new HumanRequest.SlotAsk(name, name, name, null, null, List.of())
                        : new HumanRequest.SlotAsk(spec.name(), spec.question(), spec.hint(),
                                null, null, spec.options()));
            }
            // 已自动补全项：值 + 来源（模型推断 / 日志反查 / 缺省值 / 规则提取）
            // user_override 不进：那已经是用户确认过的值，不该再挂「如有误请指出」
            List<String> notes = new ArrayList<>();
            for (SlotProvenance.Entry entry : SlotProvenance.parse(provenanceJson)) {
                if (entry.value().isBlank()) {
                    continue;
                }
                notes.add(entry.slot() + " = " + abbreviate(entry.value()) + "（" + entry.evidence() + "）");
                // user_override 只进 notes 不进 asks：那已是用户确认过的值，不该再挂「如有误请指出」
                if (missing.contains(entry.slot()) || SlotProvenance.SOURCE_OVERRIDDEN.equals(entry.source())) {
                    continue; // 缺失项已在上面的问句里，不重复
                }
                OpsSlotCatalog.Spec spec = specOf(entry.slot());
                asks.add(new HumanRequest.SlotAsk(entry.slot(),
                        spec == null ? entry.slot() : spec.question(),
                        spec == null ? null : spec.hint(), entry.value(), entry.source(),
                        spec == null ? List.of() : spec.options(), false, entry.evidence()));
            }
            HumanRequest request = notes.isEmpty()
                    ? HumanRequest.clarify("请补充以下信息，我将继续排查", asks)
                    : HumanRequest.clarify("请补充以下信息，我将继续排查", asks,
                            new HumanRequest.DecisionContext("已自动补全（如有误请直接指出）", notes));
            return MAPPER.writeValueAsString(request);
        } catch (Exception e) {
            return ""; // 序列化失败按纯文本挂起（兜底不变）
        }
    }

    /**
     * 本轮已生效的来源台账：`writes` 里有（刚被 override 翻新）就用它，否则读节点上下文。
     *
     * @param writes  本轮待落槽写入
     * @param context 节点上下文
     * @return {@code inferred_slots} JSON（可空串）
     */
    private static String effectiveProvenance(Map<String, Object> writes, NodeContext context) {
        Object pending = writes.get(AutoResolveExecutor.INFERRED_SLOTS_SLOT);
        if (pending != null) {
            return String.valueOf(pending);
        }
        return context.slots().getString(AutoResolveExecutor.INFERRED_SLOTS_SLOT, "");
    }

    /**
     * 来源台账 → 人类可读补全说明（与 {@code AutoResolveExecutor.renderNote} 同格式，
     * 但吃的是「已生效」台账，override 过的槽不会再以旧值出现在文案里）。
     *
     * @param provenanceJson 台账 JSON
     * @return 说明文本（空台账返回空串）
     */
    private static String renderNote(String provenanceJson) {
        List<SlotProvenance.Entry> entries = SlotProvenance.parse(provenanceJson);
        if (entries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("已自动补全：");
        for (SlotProvenance.Entry entry : entries) {
            sb.append("\n- ").append(entry.slot()).append(" = ")
                    .append(abbreviate(entry.value()))
                    .append("（").append(entry.evidence()).append("）");
        }
        return sb.toString();
    }

    /** 值的展示截断（说明文案里不铺整段报文）。 */
    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 60 ? value : value.substring(0, 60) + "…";
    }

    /** 目录查找（未登记的槽位名返回 null）。 */
    private static OpsSlotCatalog.Spec specOf(String name) {
        return OpsSlotCatalog.ALL.stream().filter(s -> s.name().equals(name)).findFirst().orElse(null);
    }

    /**
     * 读用户补充：优先恢复输入写入的 {@code user_clarify} 槽位，其次本轮文本。
     *
     * @param context 节点上下文
     * @return 补充文本，无则空串
     */
    private String replyOf(NodeContext context) {
        String clarified = context.slots().getString(USER_CLARIFY_SLOT, "");
        if (!clarified.isBlank()) {
            return clarified;
        }
        return context.input() == null ? "" : context.input().text();
    }

    /**
     * 槽位问句（空则回退槽位名，对齐参考实现）。
     *
     * @param name 槽位名
     * @return 问句
     */
    private static String questionOf(String name) {
        return OpsSlotCatalog.ALL.stream()
                .filter(spec -> spec.name().equals(name))
                .findFirst()
                .map(spec -> spec.question() == null || spec.question().isBlank() ? spec.name() : spec.question())
                .orElse(name);
    }
}
