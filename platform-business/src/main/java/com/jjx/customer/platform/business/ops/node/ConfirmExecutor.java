package com.jjx.customer.platform.business.ops.node;

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
import com.jjx.customer.platform.business.ops.slot.SlotProvenance;
import com.jjx.customer.platform.business.workflow.common.ActExecutor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基本信息确认门（人在环中）：槽位齐备后**不直接开诊断**，先把「我理解的这些信息」摆给用户确认。
 *
 * <p>为什么要这道门：环境/接口/时间/关键数据里混着用户明说的和系统推断/反查来的（缺省值、
 * 模型推断、日志反查），任何一个猜错，后面整段排查都跑在错的前提上——用户还看不出来。
 * 确认单把每项的来源与依据一并摊开（可逐项「改/清空」，走 {@code #override:} 确定性回传），
 * 点「确认，开始排查」才进第一阶段。</p>
 *
 * <p>非确认回复（改一项、给个方向）一律**刷新确认单再挂一次**——用户在确认页上可以反复改，
 * 不会因为顺手改了一下就被推进到诊断。自由文本认确认语是保守的关键词判定（点按钮才是主路径）。</p>
 */
public class ConfirmExecutor implements NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(ConfirmExecutor.class);

    /** 点「确认，开始排查」回传的选项值（{@code #decision:confirm}）。 */
    public static final String CONFIRM_DECISION = "confirm";

    /** 没点按钮时认的确认语（保守：不认识的一律按"还没确认"处理，刷新确认单再问）。 */
    private static final List<String> CONFIRM_WORDS =
            List.of("确认", "没问题", "可以", "开始排查", "没错", "正确", "对的", "好的", "嗯嗯");

    /** 用户直供的槽位来源标记（未登记 provenance 的已填槽，前端角标显示「你提供」）。 */
    public static final String SOURCE_USER = "user";

    private final HumanResponseInterpreter interpreter;

    /**
     * @param interpreter 回复解释器（识别确认页上的改写/方向指令；null = 只认确认回传）
     */
    public ConfirmExecutor(HumanResponseInterpreter interpreter) {
        this.interpreter = interpreter;
    }

    @Override
    public NodeType type() {
        return NodeType.CUSTOM;
    }

    @Override
    public NodeResult execute(NodeDefinition node, NodeContext context) {
        Map<String, Object> writes = new LinkedHashMap<>();
        String pending = context.slots().getString(HumanRequest.PENDING_SLOT, "");
        String reply = replyOf(context);

        // ① 重入：先消费本轮改动（可能是"改时间窗"或"确认"），再决定放行还是刷新确认单
        if (!pending.isBlank() && !reply.isBlank()) {
            HumanResponse response = interpret(reply, context, writes);
            if (isConfirmed(reply, response)) {
                writes.put(HumanRequest.PENDING_SLOT, "");
                log.info("[ops-confirm] 用户确认基本信息，进入阶段 1");
                return attach(NodeResult.dynamic(node.id(), "confirmed",
                        OpsDiagnoseWorkflowFactory.INV_THINK_NODE), writes);
            }
            log.info("[ops-confirm] 未确认为「开始排查」（回复={}），刷新确认单再问一轮", abbreviate(reply));
        }

        // ② 出确认单：全部已收集槽位 + 来源 + 依据，用户可逐项改，改完再确认
        List<HumanRequest.SlotAsk> asks = collectedAsks(context, writes);
        String prompt = "确认这些信息后开始排查（不对的项可以点「改」或「清空」）";
        HumanRequest request = new HumanRequest(HumanRequest.Kind.CONFIRM, prompt, asks, null,
                List.of(new HumanRequest.Choice(CONFIRM_DECISION, "确认，开始排查",
                        "按上面这些信息开始查日志定位问题")), true);
        log.info("[ops-confirm] 出确认单：{} 项", asks.size());
        return attach(NodeResult.suspended(node.id(), prompt)
                .withSlotWrite(HumanRequest.PENDING_SLOT, writeRequest(request)), writes);
    }

    /**
     * 已收集槽位 → 确认单条目（值 + 来源 + 依据）。
     *
     * <p>来源取自 auto-resolve 的台账；台账里没有 = 用户自己给的，标 {@link #SOURCE_USER}。</p>
     */
    private List<HumanRequest.SlotAsk> collectedAsks(NodeContext context, Map<String, Object> writes) {
        Map<String, SlotProvenance.Entry> provenance = new LinkedHashMap<>();
        for (SlotProvenance.Entry entry : SlotProvenance.parse(effectiveProvenance(writes, context))) {
            provenance.put(entry.slot(), entry);
        }
        List<HumanRequest.SlotAsk> asks = new ArrayList<>();
        for (OpsSlotCatalog.Spec spec : OpsSlotCatalog.ALL) {
            String value = context.slots().getString(spec.name(), "");
            if (value.isBlank()) {
                continue; // 缺失项不在这里问——问齐环负责
            }
            SlotProvenance.Entry entry = provenance.get(spec.name());
            asks.add(new HumanRequest.SlotAsk(spec.name(), spec.question(), spec.hint(), value,
                    entry == null ? SOURCE_USER : entry.source(),
                    spec.options(), false, entry == null ? null : entry.evidence()));
        }
        return asks;
    }

    /**
     * 本轮回复消费（改写/指令）——与问齐同口径：限定目录槽 + 按当前值调和。
     *
     * @return 结构化决议（无法解析时按确定性前缀解析）
     */
    private HumanResponse interpret(String reply, NodeContext context, Map<String, Object> writes) {
        Map<String, String> current = new LinkedHashMap<>();
        for (OpsSlotCatalog.Spec spec : OpsSlotCatalog.ALL) {
            String value = context.slots().getString(spec.name(), "");
            if (!value.isBlank()) {
                current.put(spec.name(), value);
            }
        }
        HumanResponse response = interpreter == null
                ? HumanResponse.parse(reply)
                : interpreter.interpret(reply, readRequest(context), current).response();
        Map<String, String> applied = response.mergedFills();
        if (!applied.isEmpty()) {
            writes.putAll(applied);
            Map<String, String> overridden = new LinkedHashMap<>();
            applied.forEach((slot, value) -> {
                String before = current.get(slot);
                if (before != null && !before.isBlank()) {
                    overridden.put(slot, value);
                }
            });
            if (!overridden.isEmpty()) {
                writes.put(AutoResolveExecutor.INFERRED_SLOTS_SLOT, SlotProvenance.upsertAll(
                        effectiveProvenance(writes, context), overridden,
                        SlotProvenance.SOURCE_OVERRIDDEN, "用户在确认页更正"));
            }
        }
        if (!response.directive().isBlank()) {
            writes.put(ActExecutor.USER_DIRECTIVE_SLOT, response.directive());
        }
        if (response.autonomyHint() != null) {
            AutonomyLevel hintLevel = AutonomyLevel.fromHint(response.autonomyHint());
            if (hintLevel != null) {
                writes.put(AutonomyLevel.SLOT, hintLevel.name());
            }
        }
        return response;
    }

    /** 判定是否放行：点按钮（decision=confirm）为准；打字认保守确认语。 */
    private static boolean isConfirmed(String reply, HumanResponse response) {
        if (CONFIRM_DECISION.equalsIgnoreCase(String.valueOf(response.decision()))) {
            return true;
        }
        if (!response.mergedFills().isEmpty()) {
            return false; // 本轮改了东西：先按新的值刷新确认单，别顺手就开了
        }
        String text = reply == null ? "" : reply.trim();
        return CONFIRM_WORDS.stream().anyMatch(text::contains);
    }

    /** 本轮已生效的来源台账（writes 优先，见 AskMissingExecutor 同款处理）。 */
    private static String effectiveProvenance(Map<String, Object> writes, NodeContext context) {
        Object pending = writes.get(AutoResolveExecutor.INFERRED_SLOTS_SLOT);
        return pending != null ? String.valueOf(pending)
                : context.slots().getString(AutoResolveExecutor.INFERRED_SLOTS_SLOT, "");
    }

    private HumanRequest readRequest(NodeContext context) {
        String json = context.slots().getString(HumanRequest.PENDING_SLOT, "");
        if (json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, HumanRequest.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static String writeRequest(HumanRequest request) {
        try {
            return MAPPER.writeValueAsString(request);
        } catch (Exception e) {
            return "";
        }
    }

    /** 用户回复：优先恢复输入写入的 user_clarify，其次本轮文本（与问齐同口径）。 */
    private static String replyOf(NodeContext context) {
        String clarified = context.slots().getString(ActExecutor.USER_CLARIFY_SLOT, "");
        return !clarified.isBlank() ? clarified
                : (context.input() == null ? "" : context.input().text());
    }

    private static NodeResult attach(NodeResult result, Map<String, Object> writes) {
        NodeResult current = result;
        for (Map.Entry<String, Object> entry : writes.entrySet()) {
            current = current.withSlotWrite(entry.getKey(), entry.getValue());
        }
        return current;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 40 ? oneLine : oneLine.substring(0, 40) + "…";
    }

    /** 结构化请求序列化（Jackson 线程安全，静态复用）。 */
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();
}
