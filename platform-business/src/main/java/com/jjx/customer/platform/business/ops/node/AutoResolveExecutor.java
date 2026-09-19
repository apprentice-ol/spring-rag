package com.jjx.customer.platform.business.ops.node;

import com.jjx.customer.platform.business.engine.adapter.SingleTurnModel;
import com.jjx.customer.platform.common.util.RelativeTimeParser;

import com.agentframework.crosscutting.interceptor.InterceptorAttributes;
import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.node.NodeType;
import com.agentframework.definition.tool.ToolDefinition;
import com.agentframework.engine.core.NodeContext;
import com.agentframework.engine.core.NodeResult;
import com.agentframework.engine.policy.PolicyAttributes;
import com.agentframework.engine.toolexecutor.DefaultToolExecutor;
import com.agentframework.engine.toolexecutor.ToolContext;
import com.agentframework.engine.toolexecutor.ToolResult;
import com.agentframework.engine.toolexecutor.ToolInvocation;
import com.agentframework.engine.workflowruntime.NodeExecutor;
import com.jjx.customer.platform.business.ops.OpsPrompts;
import com.jjx.customer.platform.business.ops.slot.OpsSlotCatalog;
import com.jjx.customer.platform.business.ops.slot.OpsSlotExtractor;
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
 *   <li><b>规则快通道</b>（零成本、确定性）：相对时间换算（{@link RelativeTimeParser}，
 *       含缺省值「最近30分钟」）、从已有文本正则提接口路径；</li>
 *   <li><b>LLM 推断</b>（≤1 次，计入 llm_calls）：高置信（≥0.7）推断值才生效；</li>
 *   <li><b>日志反查</b>（≤2 次 query_logs）：接口名/错误码当关键字模糊查 → 提取 traceId →
 *       traceId 精查 → 从日志行提取报文 / 接口 / 错误。</li>
 * </ol>
 *
 * <p>全部补全落 {@code inferred_slots}（JSON provenance：槽位/值/方式/依据）与
 * {@code auto_resolve_note}（人类可读），供问齐文案纠错提示与结论证据链透明化；
 * 仍缺的槽走原有问齐环兜底。补不了的静默跳过，绝不因此挂起或失败。</p>
 */
public class AutoResolveExecutor implements NodeExecutor {

    /** 补全说明槽位名（人类可读，交付层与结论证据链使用）。 */
    public static final String AUTO_NOTE_SLOT = "auto_resolve_note";
    /** 补全溯源槽位名（JSON：[{slot,value,method,evidence}]）。 */
    public static final String INFERRED_SLOTS_SLOT = "inferred_slots";

    /** LLM 推断置信度门槛。 */
    static final double CONFIDENCE_THRESHOLD = 0.7;
    private static final Pattern API_PATH = Pattern.compile("/[a-zA-Z][\\w-]*(?:/[\\w.-]+)+");
    private static final Pattern JSON_BLOCK = Pattern.compile("\\{[^{}]{10,}}");
    private static final Logger log = LoggerFactory.getLogger(AutoResolveExecutor.class);

    private final SingleTurnModel inferModel;
    private final DefaultToolExecutor toolExecutor;
    private final ObjectMapper mapper;
    private final Clock clock;

    /**
     * @param inferModel    推断模型（null = 跳过 LLM 层）
     * @param toolExecutor  工具执行管道（null = 跳过反查层）
     * @param mapper        JSON 解析
     * @param clock         时钟（时间换算与缺省窗口，测试可注入固定值）
     */
    public AutoResolveExecutor(SingleTurnModel inferModel, DefaultToolExecutor toolExecutor,
            ObjectMapper mapper, Clock clock) {
        this.inferModel = inferModel;
        this.toolExecutor = toolExecutor;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public NodeType type() {
        return NodeType.CUSTOM;
    }

    @Override
    public NodeResult execute(NodeDefinition node, NodeContext context) {
        Map<String, String> confirmed = SlotExtractExecutor.confirmedSlots(context);
        String question = context.input() == null ? "" : context.input().text();
        Map<String, Object> writes = new LinkedHashMap<>();
        List<Map<String, String>> provenance = new ArrayList<>();
        int toolCalls = 0;
        boolean llmUsed = false;

        // ---- 层 1：规则快通道（time 换算/缺省、接口路径正则） ----
        resolveTimeByRule(confirmed, question, writes, provenance);
        resolveInterfaceByRule(confirmed, question, writes, provenance);
        confirmed.putAll(asStrings(writes));

        // ---- 层 2：LLM 推断（≤1 次，只推 inferable 且仍缺的槽；带当前时间锚点供口语时间换算） ----
        List<OpsSlotCatalog.Spec> llmTargets = targets(confirmed, OpsSlotCatalog.Spec::inferable);
        if (!llmTargets.isEmpty() && inferModel != null) {
            llmUsed = true;
            inferByModel(llmTargets, confirmed, question, writes, provenance);
            confirmed.putAll(asStrings(writes));
        }

        // ---- 层 3：日志反查（≤2 次：关键字查 traceId → 精查提取报文/接口/错误） ----
        if (toolExecutor != null) {
            toolCalls = resolveFromLogs(node, context, confirmed, writes, provenance);
        }

        // ---- 汇总：重算缺口 + provenance + 说明 ----
        confirmed.putAll(asStrings(writes));
        List<String> missing = SlotExtractExecutor.missingRequired(confirmed, Map.of());
        writes.put(SlotExtractExecutor.MISSING_COUNT_SLOT, missing.size());
        writes.put(INFERRED_SLOTS_SLOT, toJson(provenance));
        writes.put(AUTO_NOTE_SLOT, renderNote(provenance));
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
            Map<String, Object> writes, List<Map<String, String>> provenance) {
        if (!isBlank(confirmed.get(OpsSlotCatalog.TIME))) {
            return;
        }
        // 先尝试用户原文里的相对表达（抽取器未抽出时兜底），再落目录缺省值
        String raw = isBlank(confirmed.get(OpsSlotCatalog.TIME)) ? question : confirmed.get(OpsSlotCatalog.TIME);
        String window = RelativeTimeParser.parse(raw, clock);
        String method;
        String evidence;
        if (window != null) {
            method = "rule";
            evidence = "输入时间表达换算：" + raw + " → " + window;
        } else {
            OpsSlotCatalog.Spec spec = specOf(OpsSlotCatalog.TIME);
            window = RelativeTimeParser.parse(spec.defaultValue(), clock);
            if (window == null) {
                return;
            }
            method = "default";
            evidence = "缺省策略：" + spec.defaultValue();
        }
        writes.put(OpsSlotCatalog.TIME, window);
        provenance.add(provenance(OpsSlotCatalog.TIME, window, method, evidence));
    }

    private void resolveInterfaceByRule(Map<String, String> confirmed, String question,
            Map<String, Object> writes, List<Map<String, String>> provenance) {
        if (!isBlank(confirmed.get(OpsSlotCatalog.INTERFACE))) {
            return;
        }
        String source = joinNonBlank(confirmed.get(OpsSlotCatalog.ERROR),
                confirmed.get(OpsSlotCatalog.PAYLOAD), confirmed.get(OpsSlotCatalog.SYMPTOMS), question);
        Matcher m = API_PATH.matcher(source);
        if (m.find()) {
            String path = m.group();
            writes.put(OpsSlotCatalog.INTERFACE, path);
            provenance.add(provenance(OpsSlotCatalog.INTERFACE, path, "rule", "文本中识别到接口路径"));
            return;
        }
        // 接口清单匹配：业务叫法（「token获取失败」「发票冲红」）在目录中找相关接口。
        // 唯一命中才补全（确定性）；多命中是冲突，留给问齐表单点选（交付层注入候选）
        List<InterfaceCatalog.Iface> candidates = InterfaceCatalog.suggest(source);
        if (candidates.size() == 1) {
            InterfaceCatalog.Iface iface = candidates.get(0);
            writes.put(OpsSlotCatalog.INTERFACE, iface.name());
            provenance.add(provenance(OpsSlotCatalog.INTERFACE, iface.name(), "rule",
                    "接口清单唯一匹配：" + iface.title()));
        }
    }

    // -----------------------------------------------------------------------------------------
    // 层 2：LLM 推断
    // -----------------------------------------------------------------------------------------

    private void inferByModel(List<OpsSlotCatalog.Spec> targets, Map<String, String> confirmed, String question,
            Map<String, Object> writes, List<Map<String, String>> provenance) {
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
            String answer = inferModel.ask("你是运维诊断的上下文补全器。",
                    OpsPrompts.composeAutoResolve(missingLines, confirmed, question, nowText));
            JsonNode array = readJsonArray(answer);
            if (array == null) {
                return;
            }
            for (JsonNode item : array) {
                String slot = item.path("slot").asText("");
                String value = item.path("value").asText("");
                double confidence = item.path("confidence").asDouble(0);
                String evidence = item.path("evidence").asText("模型推断");
                if (confidence < CONFIDENCE_THRESHOLD || isBlank(slot) || isBlank(value)) {
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
                provenance.add(provenance(slot, normalized, "llm", "confidence=" + confidence + "，" + evidence));
            }
        } catch (Exception e) {
            log.warn("[ops-auto] LLM 推断失败（静默跳过）：{}", e.getMessage());
        }
    }

    // -----------------------------------------------------------------------------------------
    // 层 3：日志反查
    // -----------------------------------------------------------------------------------------

    /**
     * @return 实际发生的工具调用次数（≤2）
     */
    private int resolveFromLogs(NodeDefinition node, NodeContext context, Map<String, String> confirmed,
            Map<String, Object> writes, List<Map<String, String>> provenance) {
        int calls = 0;
        // 关键数据槽可能是标准 traceId（全链路精查），也可能是业务键（orderNo/requestId，当关键字模糊查）
        String traceKey = confirmed.get(OpsSlotCatalog.TRACE_ID);
        boolean keyIsTraceId = looksLikeTraceId(traceKey);
        // ① 关键字反查（业务键 > 接口名 > 错误摘要 > 现象）：用户专门给的单号比接口名精准
        String keyword = firstNonBlank(
                keyIsTraceId || isBlank(traceKey) ? null : abbreviate(traceKey, 30),
                confirmed.get(OpsSlotCatalog.INTERFACE),
                abbreviate(confirmed.get(OpsSlotCatalog.ERROR), 20),
                abbreviate(confirmed.get(OpsSlotCatalog.SYMPTOMS), 20));
        boolean anythingResolvable = targets(confirmed, OpsSlotCatalog.Spec::resolvable).stream()
                .anyMatch(spec -> spec.name().equals(OpsSlotCatalog.TRACE_ID)
                        || isBlank(confirmed.get(spec.name())));
        if (!keyIsTraceId && !isBlank(keyword) && anythingResolvable) {
            // 关键数据优先：keyword 必查；时间窗只在用户明确给出时作为过滤条件，不硬造窗口挡住历史日志
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("keyword", keyword);
            args.put("limit", 10);
            String window = confirmed.get(OpsSlotCatalog.TIME);
            if (!isBlank(window)) {
                String[] range = splitWindow(window);
                args.put("start", range[0]);
                args.put("end", range[1]);
            }
            ToolResult result = runTool("query_logs", args, node, context);
            calls++;
            String traceId = extractTraceId(result);
            if (traceId != null) {
                writes.put(OpsSlotCatalog.TRACE_ID, traceId);
                provenance.add(provenance(OpsSlotCatalog.TRACE_ID, traceId, "log_query",
                        "日志反查关键字「" + keyword + "」命中"));
                confirmed.put(OpsSlotCatalog.TRACE_ID, traceId);
            }
        }
        // ② traceId 精查提取 报文/接口/错误（业务键不是 traceId，已在 ① 当关键字查过，不能再走精查）
        String traceId = firstNonBlank(asString(writes.get(OpsSlotCatalog.TRACE_ID)),
                keyIsTraceId ? traceKey : null);
        boolean needsDetail = targets(confirmed, OpsSlotCatalog.Spec::resolvable).stream()
                .anyMatch(spec -> isBlank(confirmed.get(spec.name()))
                        && isBlank(asString(writes.get(spec.name()))));
        if (traceId != null && !traceId.isBlank() && needsDetail && calls < 2) {
            ToolResult detail = runTool("query_logs", Map.of("trace_id", traceId, "limit", 30), node, context);
            calls++;
            extractFromLogLines(detail, confirmed, writes, provenance);
        }
        return calls;
    }

    /** @return 是否标准 traceId 形态（16-64 位 hex）——决定走全链路精查还是关键字模糊查 */
    private static boolean looksLikeTraceId(String value) {
        return value != null && value.matches("\\p{XDigit}{16,64}");
    }

    private void extractFromLogLines(ToolResult result, Map<String, String> confirmed,
            Map<String, Object> writes, List<Map<String, String>> provenance) {
        Object logs = result == null ? null : result.data().get("logs");
        if (!(logs instanceof List<?> lines) || lines.isEmpty()) {
            return;
        }
        // 汇总文本：接口路径 / 错误行 / 报文块 都从这里提取
        StringBuilder all = new StringBuilder();
        String firstError = null;
        for (Object line : lines) {
            if (line instanceof Map<?, ?> row) {
                Object msgRaw = row.get("msg");
                String msg = msgRaw == null ? "" : String.valueOf(msgRaw);
                all.append(msg).append('\n');
                if (firstError == null && "ERROR".equalsIgnoreCase(String.valueOf(row.get("level")))) {
                    Object exceptionRaw = row.get("exception");
                    String exception = exceptionRaw == null ? "" : String.valueOf(exceptionRaw);
                    firstError = !isBlank(exception) ? exception : abbreviate(msg, 120);
                }
            }
        }
        String text = all.toString();
        if (isBlank(confirmed.get(OpsSlotCatalog.INTERFACE)) && isBlank(asString(writes.get(OpsSlotCatalog.INTERFACE)))) {
            Matcher m = API_PATH.matcher(text);
            if (m.find()) {
                writes.put(OpsSlotCatalog.INTERFACE, m.group());
                provenance.add(provenance(OpsSlotCatalog.INTERFACE, m.group(), "log_query", "traceId 精查日志命中接口路径"));
            }
        }
        if (isBlank(confirmed.get(OpsSlotCatalog.ERROR)) && isBlank(asString(writes.get(OpsSlotCatalog.ERROR)))
                && firstError != null) {
            writes.put(OpsSlotCatalog.ERROR, firstError);
            provenance.add(provenance(OpsSlotCatalog.ERROR, firstError, "log_query", "traceId 精查命中 ERROR 级日志"));
        }
        if (isBlank(confirmed.get(OpsSlotCatalog.PAYLOAD)) && isBlank(asString(writes.get(OpsSlotCatalog.PAYLOAD)))) {
            Matcher m = JSON_BLOCK.matcher(text);
            String best = null;
            while (m.find()) {
                String candidate = m.group();
                if (best == null || candidate.length() > best.length()) {
                    best = candidate;
                }
            }
            if (best != null) {
                writes.put(OpsSlotCatalog.PAYLOAD, best);
                provenance.add(provenance(OpsSlotCatalog.PAYLOAD, abbreviate(best, 60) + "…", "log_query",
                        "traceId 精查日志提取请求报文"));
            }
        }
    }

    private String extractTraceId(ToolResult result) {
        Object logs = result == null || !result.success() ? null : result.data().get("logs");
        if (!(logs instanceof List<?> lines)) {
            return null;
        }
        // 取出现次数最多的 traceId（偶发混流时比"第一条"更稳）
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Object line : lines) {
            if (line instanceof Map<?, ?> row) {
                Object traceRaw = row.get("trace_id");
                String traceId = traceRaw == null ? "" : String.valueOf(traceRaw);
                if (!isBlank(traceId) && !"null".equals(traceId)) {
                    counts.merge(traceId, 1, Integer::sum);
                }
            }
        }
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    // -----------------------------------------------------------------------------------------
    // 工具与工具方法
    // -----------------------------------------------------------------------------------------

    /** 组装带策略属性的反查调用（属性传递对齐 ActExecutor.run）。 */
    private ToolResult runTool(String toolId, Map<String, Object> arguments,
            NodeDefinition node, NodeContext context) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("toolId", toolId);
        attributes.put(InterceptorAttributes.TRACE, context.trace());
        attributes.put(InterceptorAttributes.TRACE_PARENT, context.parentSpan());
        if (context.agent() != null) {
            attributes.put(DefaultToolExecutor.TOOL_POLICY_ATTRIBUTE, context.agent().policies().tool());
            attributes.put(DefaultToolExecutor.QUOTA_POLICY_ATTRIBUTE, context.agent().policies().quota());
        }
        Object resolvedPolicy = context.attributes().get(PolicyAttributes.RESOLVED);
        if (resolvedPolicy != null) {
            attributes.put(PolicyAttributes.RESOLVED, resolvedPolicy);
        }
        ToolContext toolContext = new ToolContext(context.session().id(), node.id(),
                context.session().traceId(), context.workspace(), context.slots(), attributes);
        try {
            return toolExecutor.execute(
                    new ToolInvocation(toolId, ToolDefinition.LATEST, arguments,
                            context.session().id(), node.id()),
                    toolContext);
        } catch (Exception e) {
            log.warn("[ops-auto] 反查工具异常（静默跳过）：{} {}", toolId, e.getMessage());
            return null;
        }
    }

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
        try {
            return mapper.writeValueAsString(provenance);
        } catch (Exception e) {
            return "[]";
        }
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
        Map<String, String> item = new LinkedHashMap<>();
        item.put("slot", slot);
        item.put("value", value);
        item.put("method", method);
        item.put("evidence", evidence);
        return item;
    }

    private static String[] splitWindow(String window) {
        if (window == null) {
            return new String[] {"", ""};
        }
        int idx = window.indexOf('~');
        return idx < 0 ? new String[] {window, ""}
                : new String[] {window.substring(0, idx), window.substring(idx + 1)};
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return "";
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
