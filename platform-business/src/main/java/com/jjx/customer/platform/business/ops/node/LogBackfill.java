package com.jjx.customer.platform.business.ops.node;

import com.agentframework.crosscutting.interceptor.InterceptorAttributes;
import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.tool.ToolDefinition;
import com.agentframework.engine.core.NodeContext;
import com.agentframework.engine.policy.PolicyAttributes;
import com.agentframework.engine.toolexecutor.DefaultToolExecutor;
import com.agentframework.engine.toolexecutor.ToolContext;
import com.agentframework.engine.toolexecutor.ToolInvocation;
import com.agentframework.engine.toolexecutor.ToolResult;
import com.jjx.customer.platform.business.ops.AutonomyPolicy;
import com.jjx.customer.platform.business.ops.slot.OpsSlotCatalog;
import com.jjx.customer.platform.business.ops.slot.SlotProvenance;
import com.jjx.customer.platform.common.util.RelativeTimeParser;
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
 * 日志反查（一手信息源）：关键字 + 时间窗 → traceId → 精查挖 接口 / 请求报文 / 响应 / 报错。
 *
 * <p><b>为什么从 {@link AutoResolveExecutor} 里独立出来</b>：反查不是"只在首轮跑一次的初始化"，
 * 而是「用当前已知信息去日志里对一把」的<b>可重入动作</b>——问齐环里用户每补一次信息
 * （改时间窗、换单号），就该拿最新的槽位再对一次。抽出后 AutoResolve（首轮）与
 * AskMissing / Confirm（挂起恢复）共用同一份实现，避免"恢复路径复制一份反查"的漂移。</p>
 *
 * <p><b>重跑边界（按挂起阶段）</b>：只有 intake 阶段（信息收集 / 确认门）的挂起恢复才重跑——
 * 那时挂起的语义是"等信息"，用户回复可能改掉反查的输入。诊断阶段（replan 决策 / 环内 ask_user）
 * 的挂起是"问方向"，基础信息已确认，重跑只会用旧输入覆盖用户确认过的前提，属于越界。</p>
 *
 * <p><b>空结果也要说话</b>：反查跑过但没命中时，{@link Outcome#note()} 给出诚实说明
 * （查了什么、在什么窗口、没查到）——静默略过会让用户分不清"查了没命中"和"根本没查"
 * （实测踩过：10 分钟窗口内无该单日志，卡片上整块消失，看着像功能坏了）。</p>
 */
public final class LogBackfill {

    private static final Logger log = LoggerFactory.getLogger(LogBackfill.class);

    /** 反查结论槽位名（人类可读；空串 = 命中或未执行，非空 = 需要向用户交代的空结果）。 */
    public static final String LOG_LOOKUP_SLOT = "log_lookup_note";

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

    private final DefaultToolExecutor toolExecutor;
    private final Clock clock;

    /**
     * @param toolExecutor 工具执行管道（null = 不做反查，本次直接返回未执行结论）
     */
    public LogBackfill(DefaultToolExecutor toolExecutor) {
        this(toolExecutor, Clock.systemDefaultZone());
    }

    /**
     * @param toolExecutor 工具执行管道（null = 不做反查）
     * @param clock        时钟（相对时间短语换算；测试注入固定值）
     */
    public LogBackfill(DefaultToolExecutor toolExecutor, Clock clock) {
        this.toolExecutor = toolExecutor;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    /**
     * 反查结论。
     *
     * @param calls     实际发生的 query_logs 调用次数（受档位额度约束）
     * @param attempted 是否真的发起了反查（关键字与时间窗齐备才会发起）
     * @param hit       是否从日志里挖到了至少一项（traceId / 接口 / 报文 / 响应 / 报错）
     * @param keyword   本次使用的关键字（未执行时为空）
     * @param window    本次使用的时间窗（未执行时为空）
     * @param note      给用户看的空结果说明；命中或未执行时为空串
     */
    public record Outcome(int calls, boolean attempted, boolean hit, String keyword, String window, String note) {

        /** @return 未执行 / 已命中时的空结论 */
        public static Outcome none() {
            return new Outcome(0, false, false, "", "", "");
        }
    }

    /**
     * 用当前已确认槽位反查日志并回填槽位。
     *
     * @param node       调用方节点（工具属性与轨迹归属）
     * @param context    节点上下文
     * @param confirmed  已确认槽位（入参读、出参就地补）
     * @param writes     待落槽写入（就地追加）
     * @param provenance 来源台账（就地追加，source=log_query）
     * @param policy     自主档位（决定是否允许反查与额度上限）
     * @return 反查结论
     */
    public Outcome resolve(NodeDefinition node, NodeContext context, Map<String, String> confirmed,
            Map<String, Object> writes, List<Map<String, String>> provenance, AutonomyPolicy policy) {
        if (toolExecutor == null) {
            return Outcome.none();
        }
        // 时间窗换算：槽位按目录约定可存原样短语（「最近3小时」），但日志查询要的是绝对窗口。
        // 抽槽路径会归一化（time 槽拿到 ISO），**解释器/用户更正路径不会**——实测恢复轮把
        // 「最近3小时」原样当 start 传下去 → 查无结果。消费方换算，两条路径才一致
        String window = normalizeWindow(confirmed.get(OpsSlotCatalog.TIME));
        if (!isBlank(window) && !window.equals(confirmed.get(OpsSlotCatalog.TIME))) {
            confirmed.put(OpsSlotCatalog.TIME, window);
            writes.put(OpsSlotCatalog.TIME, window);
        }
        int provenanceBefore = provenance.size();
        int calls = queryLogs(node, context, confirmed, writes, provenance, policy);

        String traceKey = confirmed.get(OpsSlotCatalog.TRACE_ID);
        boolean keyIsTraceId = looksLikeTraceId(traceKey);
        String keyword = firstNonBlank(
                keyIsTraceId || isBlank(traceKey) ? null : abbreviate(traceKey, 30),
                confirmed.get(OpsSlotCatalog.INTERFACE),
                abbreviate(confirmed.get(OpsSlotCatalog.ERROR), 20),
                abbreviate(confirmed.get(OpsSlotCatalog.SYMPTOMS), 20));
        boolean attempted = calls > 0;
        boolean hit = provenance.size() > provenanceBefore;
        if (!attempted && !keyIsTraceId && !isBlank(keyword) && isBlank(window)) {
            // 有线索没时间窗：按"用户口径"不扫全时段（慢且易捞到无关记录），但要如实说明，
            // 否则用户不知道该补时间——这正是"信息不全"最该反问的时刻
            return new Outcome(calls, false, false, keyword, "",
                    "未检索业务日志：缺少时间窗（只有关键字）——请补充报错的大致时间，我再去查");
        }
        if (!attempted || hit) {
            return new Outcome(calls, attempted, hit, keyword, window, "");
        }
        return new Outcome(calls, true, false, keyword, window,
                "已在「" + windowText(window) + "」内按关键字「" + abbreviate(keyword, 30)
                        + "」检索业务日志：未命中");
    }

    /**
     * 挂起恢复重跑（问齐环 / 确认门）：从现有台账续写，用最新槽位重查，并把台账与空结果说明写回。
     *
     * <p>续写而非推倒重来：首轮反查到的接口/报文不该因为这一轮换了时间窗就丢失；同槽位由
     * {@link SlotProvenance#upsert} 语义覆盖，最终仍是「每槽一条最新来源」。</p>
     *
     * @param node      调用方节点定义
     * @param context   节点上下文
     * @param confirmed 已确认槽位（本轮答复已并入；反查新补的槽值就地回流）
     * @param writes    待落槽写入（就地追加：台账 JSON + 空结果说明）
     * @param policy    自主档位
     * @return 反查结论
     */
    public Outcome rerun(NodeDefinition node, NodeContext context, Map<String, String> confirmed,
            Map<String, Object> writes, AutonomyPolicy policy) {
        List<Map<String, String>> provenance = new ArrayList<>();
        for (SlotProvenance.Entry entry : SlotProvenance.parse(existingProvenance(writes, context))) {
            provenance.add(SlotProvenance.entry(entry.slot(), entry.value(), entry.source(), entry.evidence()));
        }
        Outcome outcome = resolve(node, context, confirmed, writes, provenance, policy);
        // 反查补到的槽值回流 confirmed：否则本轮缺口判定看不到它们（与 AutoResolve 同口径）
        for (OpsSlotCatalog.Spec spec : OpsSlotCatalog.ALL) {
            Object value = writes.get(spec.name());
            if (value instanceof String text && !text.isBlank()) {
                confirmed.put(spec.name(), text);
            }
        }
        writes.put(AutoResolveExecutor.INFERRED_SLOTS_SLOT, SlotProvenance.write(provenance));
        writes.put(LOG_LOOKUP_SLOT, outcome.note());
        if (outcome.note() != null && !outcome.note().isBlank()) {
            log.info("[ops-log] 重跑反查未命中：{}", outcome.note());
        }
        return outcome;
    }

    /** 本轮已生效的台账：writes 优先（可能刚被用户的 override 翻新），否则读节点上下文。 */
    private static String existingProvenance(Map<String, Object> writes, NodeContext context) {
        Object pending = writes.get(AutoResolveExecutor.INFERRED_SLOTS_SLOT);
        return pending != null ? String.valueOf(pending)
                : context.slots().getString(AutoResolveExecutor.INFERRED_SLOTS_SLOT, "");
    }

    /**
     * 反查主体：关键字反查 traceId → traceId 精查挖细节。
     *
     * @return 实际发生的工具调用次数（L2 ≤2 / L3 ≤3）
     */
    private int queryLogs(NodeDefinition node, NodeContext context, Map<String, String> confirmed,
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
                    log.info("[ops-log] 换条件重试命中（去掉时间窗）：keyword={}", keyword);
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

    /**
     * 时间窗归一化：已是绝对窗口（含 {@code ~}）原样返回；否则按相对时间短语换算。
     *
     * <p>槽位可能存两种形态——抽槽路径归一化成 ISO 窗口，解释器/用户更正路径存原样短语。
     * 反查是消费方，这里统一换算，免得「最近3小时」被当作 start 传给日志接口（查无结果）。</p>
     *
     * @param raw 槽位原值
     * @return 绝对窗口；换算不出时原样返回
     */
    private String normalizeWindow(String raw) {
        if (isBlank(raw) || raw.contains("~")) {
            return raw;
        }
        String window = RelativeTimeParser.parse(raw, clock);
        return isBlank(window) ? raw : window;
    }

    private static final Pattern ISO_WINDOW =
            Pattern.compile("(\\d{4}-\\d{2}-\\d{2})T(\\d{2}:\\d{2})~(\\d{4}-\\d{2}-\\d{2})T(\\d{2}:\\d{2})");

    /**
     * 窗口的人读形态（卡片文案用）：{@code 2026-09-20T15:43~2026-09-20T18:43}
     * → {@code 2026-09-20 15:43 ~ 18:43}（跨天保留两端日期）。
     *
     * <p>卡片是给人看的：把 {@code T} 与重复日期原样端出去，正是"开发口径糊在卡片上"的老毛病。</p>
     */
    private static String windowText(String window) {
        if (isBlank(window)) {
            return "不限时段";
        }
        Matcher m = ISO_WINDOW.matcher(window);
        if (!m.matches()) {
            return abbreviate(window, 40);
        }
        String startDate = m.group(1);
        String startTime = m.group(2);
        String endDate = m.group(3);
        String endTime = m.group(4);
        return startDate.equals(endDate)
                ? startDate + " " + startTime + " ~ " + endTime
                : startDate + " " + startTime + " ~ " + endDate + " " + endTime;
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
                provenance.add(provenance(OpsSlotCatalog.INTERFACE, m.group(), SlotProvenance.SOURCE_LOG,
                        "traceId 精查日志命中接口路径"));
            }
        }
        if (isBlank(confirmed.get(OpsSlotCatalog.ERROR)) && isBlank(asString(writes.get(OpsSlotCatalog.ERROR)))
                && firstError != null) {
            writes.put(OpsSlotCatalog.ERROR, firstError);
            provenance.add(provenance(OpsSlotCatalog.ERROR, firstError, SlotProvenance.SOURCE_LOG,
                    "traceId 精查命中 ERROR 级日志"));
        }
        // 只从"行内明确写了这是请求报文/响应"的行里提取（报文/入参/请求体/payload｜响应/返回/response）。
        // 两者都是高影响槽：猜错了会毒化第二阶段的生成/纠正，宁可漏（让模型 ask_user 问用户）
        if (isBlank(confirmed.get(OpsSlotCatalog.PAYLOAD)) && isBlank(asString(writes.get(OpsSlotCatalog.PAYLOAD)))) {
            String best = bestJsonBlock(payloadSource.toString());
            if (best != null) {
                writes.put(OpsSlotCatalog.PAYLOAD, best);
                provenance.add(provenance(OpsSlotCatalog.PAYLOAD, abbreviate(best, 60) + "…",
                        SlotProvenance.SOURCE_LOG, "日志行明确标注的请求报文"));
            }
        }
        if (isBlank(confirmed.get(OpsSlotCatalog.RESPONSE)) && isBlank(asString(writes.get(OpsSlotCatalog.RESPONSE)))) {
            String best = bestJsonBlock(responseSource.toString());
            if (best != null) {
                writes.put(OpsSlotCatalog.RESPONSE, best);
                provenance.add(provenance(OpsSlotCatalog.RESPONSE, abbreviate(best, 60) + "…",
                        SlotProvenance.SOURCE_LOG, "日志行明确标注的业务响应"));
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
            log.warn("[ops-log] 反查工具异常（静默跳过）：{} {}", toolId, e.getMessage());
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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
