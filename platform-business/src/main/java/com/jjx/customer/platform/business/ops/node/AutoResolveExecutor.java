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
    private static final Pattern JSON_BLOCK = Pattern.compile("\\{[^{}]{10,}}");

    /** 遥测/框架内务 JSON（应用自身的 step 日志等）：首键即内务字段，明显不是业务请求报文。 */
    private static final Pattern TELEMETRY_JSON = Pattern.compile(
            "\\{\\s*\"(_event|step|step_index|stepIndex|level|logger|thread|timestamp|ts|duration_ms|durationMs)\"");

    /** 报文槽长度上限：超过这个长度的"JSON 块"多半是日志片段而非真实报文。 */
    private static final int MAX_PAYLOAD_LENGTH = 4000;

    /** 响应提取的准入标记：日志行自己写明是响应/返回才提取（与报文同口径：宁可漏不可错）。 */
    private static final Pattern RESPONSE_MARKER = Pattern.compile(
            "响应|返回报文|返回结果|返回体|response|respBody", Pattern.CASE_INSENSITIVE);

    /** 报文提取的准入标记：日志行自己写明是请求报文才提取（业务系统日志的常见写法）。 */
    private static final Pattern PAYLOAD_MARKER = Pattern.compile(
            "报文|入参|请求体|请求参数|请求报文|payload|requestBody|request_body|reqBody",
            Pattern.CASE_INSENSITIVE);
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
    /** auto-resolve 骨架正文来源（绑定包覆盖优先，classpath 兜底；null 时跳过推断层） */
    private final java.util.function.Function<String, String> promptBody;

    public AutoResolveExecutor(SingleTurnModel inferModel, DefaultToolExecutor toolExecutor,
            ObjectMapper mapper, Clock clock, java.util.function.Function<String, String> promptBody) {
        this.inferModel = inferModel;
        this.toolExecutor = toolExecutor;
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
        if (toolExecutor != null) {
            toolCalls = resolveFromLogs(node, context, confirmed, writes, provenance, policy);
            confirmed.putAll(asStrings(writes));
            // 挖出的证据（报文里的接口路径、报错里的业务叫法）回头喂给规则层——否则会出现
            // 「日志里明明有线索，卡片还在问接口是哪个」
            resolveInterfaceByRule(confirmed, question, writes, provenance, policy);
            confirmed.putAll(asStrings(writes));
        }

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
    // 层 3：日志反查
    // -----------------------------------------------------------------------------------------

    /**
     * @return 实际发生的工具调用次数（L2 ≤2 / L3 ≤3）
     */
    private int resolveFromLogs(NodeDefinition node, NodeContext context, Map<String, String> confirmed,
            Map<String, Object> writes, List<Map<String, String>> provenance, AutonomyPolicy policy) {
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
        boolean anythingResolvable = targets(confirmed, policy::allowsResolve).stream()
                .anyMatch(spec -> spec.name().equals(OpsSlotCatalog.TRACE_ID)
                        || isBlank(confirmed.get(spec.name())));
        // 反查前提（用户口径）：关键字 + 时间窗**都要有**才去翻日志——两者都是用户给的或推断层填的。
        // 只有关键字没时间窗 = 全时段扫描：慢，而且很容易捞到无关记录（实测把平台自己的日志当业务日志）
        String window = confirmed.get(OpsSlotCatalog.TIME);
        if (!keyIsTraceId && !isBlank(keyword) && !isBlank(window) && anythingResolvable) {
            String traceId = searchByKeyword(keyword, window, node, context);
            calls++;
            // 换条件重试：带时间窗查无果 → 去掉窗口再查一次（用户给的时间窗本身可能就是错的）。
            // 只有预算还留得下后续精查时才做——L2 的两次额度刚好是「关键字 + 精查」，L3 才有余量
            if (traceId == null && !isBlank(window) && calls + 1 < policy.maxResolveCalls()) {
                traceId = searchByKeyword(keyword, "", node, context);
                calls++;
                if (traceId != null) {
                    log.info("[ops-auto] 换条件重试命中（去掉时间窗）：keyword={}", keyword);
                }
            }
            if (traceId != null) {
                writes.put(OpsSlotCatalog.TRACE_ID, traceId);
                provenance.add(provenance(OpsSlotCatalog.TRACE_ID, traceId, SlotProvenance.SOURCE_LOG,
                        "日志反查关键字「" + keyword + "」命中"));
                confirmed.put(OpsSlotCatalog.TRACE_ID, traceId);
            }
        }
        // ② traceId 精查提取 报文/接口/错误（业务键不是 traceId，已在 ① 当关键字查过，不能再走精查）
        String traceId = firstNonBlank(asString(writes.get(OpsSlotCatalog.TRACE_ID)),
                keyIsTraceId ? traceKey : null);
        boolean needsDetail = targets(confirmed, policy::allowsResolve).stream()
                .anyMatch(spec -> isBlank(confirmed.get(spec.name()))
                        && isBlank(asString(writes.get(spec.name()))));
        if (traceId != null && !traceId.isBlank() && needsDetail && calls < policy.maxResolveCalls()) {
            ToolResult detail = runTool("query_logs", Map.of("trace_id", traceId, "limit", 30), node, context);
            calls++;
            extractFromLogLines(detail, confirmed, writes, provenance);
        }
        return calls;
    }

    /**
     * 关键字模糊查一次，返回命中的 traceId。
     *
     * @param keyword 关键字（业务键 / 接口名 / 错误摘要）
     * @param window  时间窗（{@code start~end}，空 = 不限时段）
     * @return 命中最多出现的 traceId；无命中返回 null
     */
    private String searchByKeyword(String keyword, String window, NodeDefinition node, NodeContext context) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("keyword", keyword);
        args.put("limit", 10);
        if (!isBlank(window)) {
            String[] range = splitWindow(window);
            args.put("start", range[0]);
            args.put("end", range[1]);
        }
        return extractTraceId(runTool("query_logs", args, node, context));
    }

    /**
     * 取文本里最长的合法 JSON 块（超长/无块返回 null）。
     *
     * @param text 候选来源文本（已按行内标记过滤）
     * @return JSON 文本；无可用块返回 null
     */
    private static String bestJsonBlock(String text) {
        Matcher m = JSON_BLOCK.matcher(text);
        String best = null;
        while (m.find()) {
            String candidate = m.group();
            if (candidate.length() > MAX_PAYLOAD_LENGTH) {
                continue;
            }
            if (best == null || candidate.length() > best.length()) {
                best = candidate;
            }
        }
        return best;
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
        // 汇总文本：接口路径 / 错误行 / 报文块 都从这里提取。
        // 应用自身的遥测行（{"_event":"step.output",...}）整行跳过——里面的 prompt 模板/步骤 JSON
        // 会被"最长 JSON 块"选中当成报文（实测卡片上摆出 replan 的输出协议模板）
        StringBuilder all = new StringBuilder();
        StringBuilder payloadSource = new StringBuilder(); // 只收"自己写明是报文"的行
        StringBuilder responseSource = new StringBuilder(); // 只收"自己写明是响应"的行
        String firstError = null;
        for (Object line : lines) {
            if (line instanceof Map<?, ?> row) {
                Object msgRaw = row.get("msg");
                String msg = msgRaw == null ? "" : String.valueOf(msgRaw);
                if (msg.isBlank() || TELEMETRY_JSON.matcher(msg).lookingAt()) {
                    continue;
                }
                all.append(msg).append('\n');
                if (PAYLOAD_MARKER.matcher(msg).find()) {
                    payloadSource.append(msg).append('\n');
                }
                if (RESPONSE_MARKER.matcher(msg).find()) {
                    responseSource.append(msg).append('\n');
                }
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
                provenance.add(provenance(OpsSlotCatalog.INTERFACE, m.group(), SlotProvenance.SOURCE_LOG, "traceId 精查日志命中接口路径"));
            }
        }
        if (isBlank(confirmed.get(OpsSlotCatalog.ERROR)) && isBlank(asString(writes.get(OpsSlotCatalog.ERROR)))
                && firstError != null) {
            writes.put(OpsSlotCatalog.ERROR, firstError);
            provenance.add(provenance(OpsSlotCatalog.ERROR, firstError, SlotProvenance.SOURCE_LOG, "traceId 精查命中 ERROR 级日志"));
        }
        // 只从"行内明确写了这是请求报文/响应"的行里提取（报文/入参/请求体/payload｜响应/返回/response）。
        // 两者都是高影响槽：猜错了会毒化第二阶段的生成/纠正，宁可漏（让模型 ask_user 问用户）
        if (isBlank(confirmed.get(OpsSlotCatalog.PAYLOAD)) && isBlank(asString(writes.get(OpsSlotCatalog.PAYLOAD)))) {
            String best = bestJsonBlock(payloadSource.toString());
            if (best != null) {
                writes.put(OpsSlotCatalog.PAYLOAD, best);
                provenance.add(provenance(OpsSlotCatalog.PAYLOAD, abbreviate(best, 60) + "…", SlotProvenance.SOURCE_LOG,
                        "日志行明确标注的请求报文"));
            }
        }
        if (isBlank(confirmed.get(OpsSlotCatalog.RESPONSE)) && isBlank(asString(writes.get(OpsSlotCatalog.RESPONSE)))) {
            String best = bestJsonBlock(responseSource.toString());
            if (best != null) {
                writes.put(OpsSlotCatalog.RESPONSE, best);
                provenance.add(provenance(OpsSlotCatalog.RESPONSE, abbreviate(best, 60) + "…", SlotProvenance.SOURCE_LOG,
                        "日志行明确标注的业务响应"));
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
        return SlotProvenance.entry(slot, value, method, evidence);
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
