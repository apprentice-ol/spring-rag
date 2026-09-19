package com.jjx.customer.platform.business.ops.node;
import com.jjx.customer.platform.common.util.RelativeTimeParser;

import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.node.NodeType;
import com.agentframework.engine.core.NodeContext;
import com.agentframework.engine.core.NodeResult;
import com.agentframework.engine.workflowruntime.NodeExecutor;
import com.jjx.customer.platform.business.ops.slot.OpsSlotCatalog;
import com.jjx.customer.platform.business.ops.slot.OpsSlotExtractor;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 槽位抽取节点执行器（O1/O3 前半）：入口把用户输入解析成业务槽位，并计算必填缺口。
 *
 * <p>语义对齐参考实现的 {@code extractSlotsIfNeeded + normalizeSlots}：
 * 声明式归一（environment 别名折叠）→ 已确认值优先、只填空缺 →
 * 写 {@code missing_count}（必填缺失数，供 {@code slots_gate} 表达式）。
 * 无空缺或模型不可用时零 LLM 短路。</p>
 *
 * <p>time 槽在抽取当下就规范化为 ISO 窗口：口语原文（「昨天下午」「今天下午3点」）
 * 若不换算，必填齐备的会话不再经过 auto-resolve，原文会直通 query_logs 查错窗口。
 * 新抽取值与预填/回灌的已确认值都覆盖。</p>
 */
public class SlotExtractExecutor implements NodeExecutor {

    /** 必填缺失计数槽位名（slots_gate / slots_regate 的判定依据）。 */
    public static final String MISSING_COUNT_SLOT = "missing_count";
    /** 抽槽原始输出槽位名。 */
    public static final String EXTRACT_RAW_SLOT = "slot_extract_raw";

    /** 已规范化的 time 槽形态：{@code yyyy-MM-ddTHH:mm~yyyy-MM-ddTHH:mm}。 */
    private static final Pattern ISO_WINDOW =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}~\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}");

    private final OpsSlotExtractor extractor;
    private final Clock clock;

    /**
     * @param extractor 槽位抽取器（时间规范化用系统时钟）
     */
    public SlotExtractExecutor(OpsSlotExtractor extractor) {
        this(extractor, Clock.systemDefaultZone());
    }

    /**
     * @param extractor 槽位抽取器
     * @param clock     时钟（口语时间 → ISO 窗口换算，测试可注入固定值）
     */
    public SlotExtractExecutor(OpsSlotExtractor extractor, Clock clock) {
        this.extractor = extractor;
        this.clock = clock;
    }

    @Override
    public NodeType type() {
        return NodeType.CUSTOM;
    }

    @Override
    public NodeResult execute(NodeDefinition node, NodeContext context) {
        Map<String, String> confirmed = confirmedSlots(context);
        String text = context.input() == null ? "" : context.input().text();

        Map<String, String> extracted = extractor.extract(text, confirmed);
        normalizeTimeWindow(extracted, confirmed);
        Map<String, Object> writes = new LinkedHashMap<>(extracted);

        List<String> missing = missingRequired(confirmed, extracted);
        writes.put(MISSING_COUNT_SLOT, missing.size());
        writes.put(EXTRACT_RAW_SLOT, extracted.isEmpty() ? "" : String.join(",", extracted.keySet()));
        // O9 计数口径：抽槽若真的调了模型（有空白 + 有输入 + 模型可用）也要计入 LLM 调用数
        if (extractor.available() && !text.isBlank() && !missing.isEmpty() && extracted.isEmpty()) {
            Object used = context.slots().get("llm_calls");
            writes.put("llm_calls", (used instanceof Number n ? n.intValue() : 0) + 1);
        }
        return NodeResult.completed(node.id(),
                extracted.isEmpty() ? "无新槽位" : "抽取 " + extracted.size() + " 项", writes);
    }

    /**
     * time 口语原文 → ISO 窗口（新抽取值优先；已确认的原文换算结果也要落 writes 覆盖旧值）。
     *
     * @param extracted 本轮抽取值（原地修正）
     * @param confirmed 已确认值（含预填/表单回灌的原文）
     * @return 换算结果写入 extracted（供调用方落 writes）
     */
    private void normalizeTimeWindow(Map<String, String> extracted, Map<String, String> confirmed) {
        String raw = extracted.containsKey(OpsSlotCatalog.TIME)
                ? extracted.get(OpsSlotCatalog.TIME)
                : confirmed.get(OpsSlotCatalog.TIME);
        if (raw == null || raw.isBlank() || ISO_WINDOW.matcher(raw).matches()) {
            return;
        }
        String parsed = RelativeTimeParser.parse(raw, clock);
        if (parsed != null) {
            extracted.put(OpsSlotCatalog.TIME, parsed);
        }
    }

    /**
     * 当前已确认的目录槽位（已应用归一化）。
     *
     * @param context 节点上下文
     * @return 槽位名 → 值
     */
    static Map<String, String> confirmedSlots(NodeContext context) {
        Map<String, String> confirmed = new LinkedHashMap<>();
        for (OpsSlotCatalog.Spec spec : OpsSlotCatalog.ALL) {
            String value = context.slots().getString(spec.name(), "");
            if (!value.isBlank()) {
                confirmed.put(spec.name(), spec.normalize(value));
            }
        }
        return confirmed;
    }

    /**
     * 必填缺失名单（目录序）。
     *
     * @param confirmed 已确认值
     * @param extracted 本轮抽取值（填空缺）
     * @return 缺失的槽位名列表
     */
    public static List<String> missingRequired(Map<String, String> confirmed, Map<String, String> extracted) {
        return OpsSlotCatalog.ALL.stream()
                .filter(spec -> spec.required())
                .filter(spec -> isBlank(confirmed.get(spec.name())) && isBlank(extracted.get(spec.name())))
                .map(OpsSlotCatalog.Spec::name)
                .toList();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
