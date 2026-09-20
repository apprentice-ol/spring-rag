package com.jjx.customer.platform.business.ops.node;

import com.jjx.customer.platform.business.engine.adapter.SingleTurnModel;
import com.jjx.customer.platform.common.util.RelativeTimeParser;

import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.node.NodeType;
import com.agentframework.engine.core.NodeContext;
import com.agentframework.engine.core.NodeResult;
import com.agentframework.engine.workflowruntime.NodeExecutor;
import com.jjx.customer.platform.business.ops.AutonomyLevel;
import com.jjx.customer.platform.business.ops.AutonomyPolicy;
import com.jjx.customer.platform.business.ops.OpsPrompts;
import com.jjx.customer.platform.business.ops.slot.OpsSlotCatalog;
import com.jjx.customer.platform.business.ops.slot.OpsSlotExtractor;
import com.jjx.customer.platform.business.ops.slot.SlotProvenance;
import com.jjx.customer.platform.business.ops.tool.InterfaceCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 槽位自主补全节点执行器（问用户之前的自救层）：按目录声明的补全策略逐槽补全，
 * 三层递进（成本递增）——
 *
 * <ol>
 *   <li><b>规则快通道</b>（零成本、确定性）：相对时间换算（{@link RelativeTimeParser}）、
 *       从已有文本正则提接口路径、接口清单唯一匹配，以及目录缺省值（如 time 的「最近30分钟」）；</li>
 *   <li><b>LLM 推断</b>（≤1 次，计入 llm_calls）：置信度过线（L2 ≥0.7 / L3 ≥0.5）的推断值才生效；</li>
 *   <li><b>日志反查</b>（L2 ≤2 次 / L3 ≤3 次 query_logs）：接口名/错误码当关键字模糊查 →
 *       提取 traceId → traceId 精查 → 从日志行提取报文 / 接口 / 错误。</li>
 * </ol>
 *
 * <p><b>自主档位（P3）</b>：三层各自受 {@link AutonomyPolicy}（目录声明 ∩ 会话档位）管辖——
 * L1 只做用户原文的确定性提取，不代填缺省、不推断、不反查；L3 把推断门槛降到 0.5、
 * 反查额度提到 3 次（多出的一轮用于带窗查无果时去掉时间窗重试）。</p>
 *
 * <p>全部补全落 {@code inferred_slots}（JSON provenance：槽位/值/来源/依据，见
 * {@link SlotProvenance}）与 {@code auto_resolve_note}（人类可读），供问齐文案纠错提示、
 * 前端「模型推断」角标与结论证据链透明化；仍缺的槽走原有问齐环兜底。
 * 补不了的静默跳过，绝不因此挂起或失败。</p>
 */
public class AutoResolveExecutor implements NodeExecutor {

    /** 补全说明槽位名（人类可读，交付层与结论证据链使用）。 */
    public static final String AUTO_NOTE_SLOT = "auto_resolve_note";
    /** 补全溯源槽位名（JSON：[{slot,value,method,evidence}]）。 */
    public static final String INFERRED_SLOTS_SLOT = "inferred_slots";

    private static final Pattern API_PATH = Pattern.compile("/[a-zA-Z][\\w-]*(?:/[\\w.-]+)+");

    private static final Logger log = LoggerFactory.getLogger(AutoResolveExecutor.class);

    private final SingleTurnModel inferModel;
    private final ObjectMapper mapper;
    private final Clock clock;

    /** 日志反查（从本类抽出为可复用组件：问齐环挂起恢复时也用同一份实现重跑）。 */
    private final LogBackfill logBackfill;

    /**
     * @param inferModel    推断模型（null = 跳过 LLM 层）
     * @param toolExecutor  工具执行管道（null = 跳过反查层）
     * @param mapper        JSON 解析
     * @param clock         时钟（时间换算与缺省窗口，测试可注入固定值）
     */
    /** auto-resolve 骨架正文来源（绑定包覆盖优先，classpath 兜底；null 时跳过推断层） */
    private final java.util.function.Function<String, String> promptBody;

    /**
     * @param inferModel  推断模型（null = 跳过 LLM 层）
     * @param logBackfill 日志反查组件（null = 跳过反查层；与问齐/确认节点共用同一实现）
     * @param mapper      JSON 解析
     * @param clock       时钟
     * @param promptBody  auto-resolve 骨架正文来源
     */
    public AutoResolveExecutor(SingleTurnModel inferModel, LogBackfill logBackfill,
            ObjectMapper mapper, Clock clock, java.util.function.Function<String, String> promptBody) {
        this.inferModel = inferModel;
        this.logBackfill = logBackfill;
        this.mapper = mapper;
        this.clock = clock;
        this.promptBody = promptBody;
    }

    @Override
    public NodeType type() {
        return NodeType.CUSTOM;
    }

    @Override
    public NodeResult execute(NodeDefinition node, NodeContext context) {
        Map<String, String> confirmed = SlotExtractExecutor.confirmedSlots(context);
        String question = context.input() == null ? "" : context.input().text();
        // 会话档位（人在环中 P3）：目录声明 ∩ 档位 = 本次允许的自主边界（交集偏保守）
        AutonomyPolicy policy = AutonomyPolicy.of(context.slots().getString(AutonomyLevel.SLOT, ""));
        Map<String, Object> writes = new LinkedHashMap<>();
        List<Map<String, String>> provenance = new ArrayList<>();
        int toolCalls = 0;
        boolean llmUsed = false;

        // ---- 层 1：规则快通道（time 换算/缺省、接口路径正则）----
        // 顺带为日志反查备好两个前提：时间窗 + 关键字（接口名是关键字的首选来源）
        resolveTimeByRule(confirmed, question, writes, provenance, policy);
        resolveInterfaceByRule(confirmed, question, writes, provenance, policy);
        confirmed.putAll(asStrings(writes));

        // ---- 层 2：日志反查（一手信息源：关键字 + 时间窗 → traceId → 精查挖 接口/请求报文/响应/报错）----
        // 无反查组件（未接工具管道）时整层跳过，与旧行为一致
        LogBackfill.Outcome lookup = logBackfill == null
                ? LogBackfill.Outcome.none()
                : logBackfill.resolve(node, context, confirmed, writes, provenance, policy);
        toolCalls = lookup.calls();
        confirmed.putAll(asStrings(writes));
        // 挖出的证据（报文里的接口路径、报错里的业务叫法）回头喂给规则层——否则会出现
        // 「日志里明明有线索，卡片还在问接口是哪个」
        resolveInterfaceByRule(confirmed, question, writes, provenance, policy);
        confirmed.putAll(asStrings(writes));

        // ---- 层 3：LLM 推断（≤1 次，补日志挖不到的那部分；此时上下文已含日志证据）----
        List<OpsSlotCatalog.Spec> llmTargets = targets(confirmed, policy::allowsInfer);
        if (!llmTargets.isEmpty() && inferModel != null) {
            llmUsed = true;
            inferByModel(llmTargets, confirmed, question, writes, provenance, policy);
            confirmed.putAll(asStrings(writes));
        }

        // ---- 汇总：重算缺口 + provenance + 说明 ----
        confirmed.putAll(asStrings(writes));
        List<String> missing = SlotExtractExecutor.missingRequired(confirmed, Map.of());
        writes.put(SlotExtractExecutor.MISSING_COUNT_SLOT, missing.size());
        writes.put(INFERRED_SLOTS_SLOT, toJson(provenance));
        writes.put(AUTO_NOTE_SLOT, renderNote(provenance));
        // 反查空结果如实透出（空串 = 命中或未执行）：问齐卡片据此向用户反问"信息是否准确"，
        // 而不是让"已自动补全"整块静默消失
        writes.put(LogBackfill.LOG_LOOKUP_SLOT, lookup.note() == null ? "" : lookup.note());
        if (llmUsed) {
            Object used = context.slots().get("llm_calls");
            writes.put("llm_calls", (used instanceof Number n ? n.intValue() : 0) + 1);
        }
        log.info("[ops-auto] 自主补全：{} 项（工具 {} 次），仍缺必填 {}：{}", provenance.size(), toolCalls,
                missing.size(), missing);
        return NodeResult.completed(node.id(),
                provenance.isEmpty() ? "无可自主补全项" : "自主补全 " + provenance.size() + " 项", writes);
    }

    // -----------------------------------------------------------------------------------------
    // 层 1：规则
    // -----------------------------------------------------------------------------------------

    private void resolveTimeByRule(Map<String, String> confirmed, String question,
            Map<String, Object> writes, List<Map<String, String>> provenance, AutonomyPolicy policy) {
        if (!isBlank(confirmed.get(OpsSlotCatalog.TIME))) {
            return;
        }
        // 先尝试用户原文里的相对表达（抽取器未抽出时兜底），再落目录缺省值
        String raw = isBlank(confirmed.get(OpsSlotCatalog.TIME)) ? question : confirmed.get(OpsSlotCatalog.TIME);
        String window = RelativeTimeParser.parse(raw, clock);
        String method;
        String evidence;
        if (window != null) {
            // 用户自己说的相对时间 → 换算，不是假设，任何档位都做
            method = SlotProvenance.SOURCE_RULE;
            evidence = "输入时间表达换算：" + raw + " → " + window;
        } else {
            if (!policy.allowsCatalogDefault()) {
                return; // L1：不代填缺省窗口，缺时间就问用户
            }
            OpsSlotCatalog.Spec spec = specOf(OpsSlotCatalog.TIME);
            window = RelativeTimeParser.parse(spec.defaultValue(), clock);
            if (window == null) {
                return;
            }
            method = SlotProvenance.SOURCE_DEFAULT;
            evidence = "缺省策略：" + spec.defaultValue();
        }
        writes.put(OpsSlotCatalog.TIME, window);
        provenance.add(provenance(OpsSlotCatalog.TIME, window, method, evidence));
    }

    private void resolveInterfaceByRule(Map<String, String> confirmed, String question,
            Map<String, Object> writes, List<Map<String, String>> provenance, AutonomyPolicy policy) {
        if (!isBlank(confirmed.get(OpsSlotCatalog.INTERFACE))) {
            return;
        }
        String source = joinNonBlank(confirmed.get(OpsSlotCatalog.ERROR),
                confirmed.get(OpsSlotCatalog.PAYLOAD), confirmed.get(OpsSlotCatalog.SYMPTOMS), question);
        Matcher m = API_PATH.matcher(source);
        if (m.find()) {
            String path = m.group();
            writes.put(OpsSlotCatalog.INTERFACE, path);
            provenance.add(provenance(OpsSlotCatalog.INTERFACE, path, SlotProvenance.SOURCE_RULE,
                    "文本中识别到接口路径"));
            return;
        }
        // 接口清单匹配：业务叫法（「token获取失败」「发票冲红」）在目录中找相关接口。
        // 这是"猜"（目录联想），受档位管辖：L1 不猜，缺接口就问用户
        if (!policy.allowsInfer(specOf(OpsSlotCatalog.INTERFACE))) {
            return;
        }
        // 唯一命中才补全（确定性）；多命中是冲突，留给问齐表单点选（交付层注入候选）
        List<InterfaceCatalog.Iface> candidates = InterfaceCatalog.suggest(source);
        if (candidates.size() == 1) {
            InterfaceCatalog.Iface iface = candidates.get(0);
            writes.put(OpsSlotCatalog.INTERFACE, iface.name());
            provenance.add(provenance(OpsSlotCatalog.INTERFACE, iface.name(), SlotProvenance.SOURCE_RULE,
                    "接口清单唯一匹配：" + iface.title()));
        }
    }

    // -----------------------------------------------------------------------------------------
    // 层 2：LLM 推断
    // -----------------------------------------------------------------------------------------

    private void inferByModel(List<OpsSlotCatalog.Spec> targets, Map<String, String> confirmed, String question,
            Map<String, Object> writes, List<Map<String, String>> provenance, AutonomyPolicy policy) {
        try {
            List<String[]> missingLines = targets.stream()
                    .map(spec -> new String[] {spec.name(),
                            spec.extractionHint() != null ? spec.extractionHint() : spec.question()})
                    .toList();
            // 当前时间锚点：没有它模型不知道「今天/昨天」对应哪天，口语时间无从换算
            java.time.LocalDateTime now = java.time.LocalDateTime.now(clock);
            String nowText = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
                    + "（" + now.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL,
                            java.util.Locale.CHINESE) + "，" + java.time.ZoneId.systemDefault().getId() + "）";
            String template = promptBody.apply(OpsPrompts.AUTO_RESOLVE_ASSET);
            if (template == null || template.isBlank()) {
                return;
            }
            String answer = inferModel.ask("你是运维诊断的上下文补全器。",
                    OpsPrompts.renderAutoResolve(template, missingLines, confirmed, question, nowText));
            JsonNode array = readJsonArray(answer);
            if (array == null) {
                return;
            }
            for (JsonNode item : array) {
                String slot = item.path("slot").asText("");
                String value = item.path("value").asText("");
                double confidence = item.path("confidence").asDouble(0);
                String evidence = item.path("evidence").asText("模型推断");
                if (confidence < policy.inferConfidence() || isBlank(slot) || isBlank(value)) {
                    continue;
                }
                OpsSlotCatalog.Spec spec = targets.stream()
                        .filter(candidate -> candidate.name().equals(slot))
                        .findFirst().orElse(null);
                // 目标可能已被本层前一项或规则层补上，仍缺才生效
                if (spec == null || !isBlank(confirmed.get(slot)) || !isBlank(asString(writes.get(slot)))) {
                    continue;
                }
                String normalized = spec.normalize(value.trim());
                writes.put(slot, normalized);
                provenance.add(provenance(slot, normalized, SlotProvenance.SOURCE_INFERRED,
                        "confidence=" + confidence + "，" + evidence));
            }
        } catch (Exception e) {
            log.warn("[ops-auto] LLM 推断失败（静默跳过）：{}", e.getMessage());
        }
    }

    // -----------------------------------------------------------------------------------------
    // 工具与工具方法
    // -----------------------------------------------------------------------------------------

    private List<OpsSlotCatalog.Spec> targets(Map<String, String> confirmed,
            java.util.function.Predicate<OpsSlotCatalog.Spec> capability) {
        return OpsSlotCatalog.ALL.stream()
                .filter(spec -> isBlank(confirmed.get(spec.name())))
                .filter(capability)
                .toList();
    }

    private static OpsSlotCatalog.Spec specOf(String name) {
        return OpsSlotCatalog.ALL.stream().filter(spec -> spec.name().equals(name)).findFirst().orElseThrow();
    }

    private JsonNode readJsonArray(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(raw);
        } catch (Exception ignored) {
            // 兼容 markdown 围栏：截取第一个 [ 到最后一个 ]
            int start = raw.indexOf('[');
            int end = raw.lastIndexOf(']');
            if (start >= 0 && end > start) {
                try {
                    return mapper.readTree(raw.substring(start, end + 1));
                } catch (Exception fatal) {
                    return null;
                }
            }
            return null;
        }
    }

    private String toJson(List<Map<String, String>> provenance) {
        // 统一走 SlotProvenance 的序列化入口（与恢复重跑路径同一格式，避免两处写出不同形态）
        return SlotProvenance.write(provenance);
    }

    private String renderNote(List<Map<String, String>> provenance) {
        if (provenance.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("已自动补全：");
        for (Map<String, String> item : provenance) {
            sb.append("\n- ").append(item.get("slot")).append(" = ").append(abbreviate(item.get("value"), 60))
                    .append("（").append(item.get("evidence")).append("）");
        }
        return sb.toString();
    }

    private static Map<String, String> provenance(String slot, String value, String method, String evidence) {
        return SlotProvenance.entry(slot, value, method, evidence);
    }

    private static String joinNonBlank(String... values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (!isBlank(value)) {
                sb.append(value).append('\n');
            }
        }
        return sb.toString();
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Map<String, String> asStrings(Map<String, Object> writes) {
        Map<String, String> out = new LinkedHashMap<>();
        writes.forEach((key, value) -> {
            if (value instanceof String s) {
                out.put(key, s);
            }
        });
        return out;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
