package com.jjx.customer.platform.business.ops.tool;

import com.agentframework.definition.tool.ToolParameter;
import com.agentframework.definition.tool.ToolSchema;
import com.agentframework.engine.toolexecutor.Tool;
import com.agentframework.engine.toolexecutor.ToolContext;
import com.agentframework.engine.toolexecutor.ToolInput;
import com.agentframework.engine.toolexecutor.ToolResult;
import com.jjx.customer.platform.business.ops.OpsProperties;
import com.jjx.customer.platform.observe.openobserve.OpenObserveLogQueryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 日志查询工具（T1）：能力面工具，只产出数据与结构化明细，不改变循环走向。
 *
 * <p>两条查询路径对齐参考实现 {@code OpsQueryLogsTool}：
 * ① 有 traceId → 精查全链路（缓存 24h，历史日志不可变）；
 * ② 按时间窗 + 可选关键字/级别模糊查（缓存 60s，end 落分钟桶）。
 * 空结果回喂「建议补槽」文本，由模型自行决定是否转 ask_user。
 * 缓存为进程内 TTL 实现（键形/TTL 语义与参考一致；参考落 Redis，本服务无 Redis 依赖，
 * 属记录在案的有意偏离）。</p>
 */
public class QueryLogsTool implements Tool {

    /** 工具 id。 */
    public static final String TOOL_ID = "query_logs";

    private static final Logger log = LoggerFactory.getLogger(QueryLogsTool.class);

    private static final long DEFAULT_WINDOW_MS = 30 * 60_000L;

    private final OpenObserveLogQueryClient client;

    private final OpsProperties.LogsCache cacheProps;

    private final OpsProperties.OpenObserve fields;

    /** 进程内 TTL 缓存（key → 带过期的缓存值）。 */
    private final ConcurrentHashMap<String, TimedEntry> cache = new ConcurrentHashMap<>();

    /**
     * @param client      OpenObserve 客户端
     * @param cacheProps  缓存配置
     * @param fields      字段名配置（正文/级别/链路列）
     */
    public QueryLogsTool(OpenObserveLogQueryClient client, OpsProperties.LogsCache cacheProps,
            OpsProperties.OpenObserve fields) {
        this.client = client;
        this.cacheProps = cacheProps;
        this.fields = fields;
    }

    /**
     * @param client     OpenObserve 客户端
     * @param cacheProps 缓存配置
     */
    public QueryLogsTool(OpenObserveLogQueryClient client, OpsProperties.LogsCache cacheProps) {
        this(client, cacheProps, null);
    }

    @Override
    public String id() {
        return TOOL_ID;
    }

    @Override
    public ToolSchema schema() {
        return ToolSchema.of(TOOL_ID,
                "查询服务日志排障。两种方式：① 有 traceId 时按 traceId 精查全链路日志；"
                        + "② 按时间窗（start/end，ISO-8601）加可选关键字/级别模糊查；"
                        + "start/end 均缺省时不限时间全量按关键字查（配 limit 控制条数）——"
                        + "关键数据（流水号/orderNo）优先命中，时间只在用户明确给出时作过滤条件。",
                ToolParameter.of("trace_id", "string"),
                ToolParameter.of("start", "string"),
                ToolParameter.of("end", "string"),
                ToolParameter.of("keyword", "string"),
                ToolParameter.of("level", "string"),
                ToolParameter.of("limit", "number", 30));
    }

    @Override
    public ToolResult invoke(ToolInput input, ToolContext context) {
        if (!client.isAvailable()) {
            return ToolResult.failed("日志查询服务未配置，无法查询日志："
                    + client.unavailableReason() + "。请补全配置后重试，或改用 traceId 精查。");
        }
        String traceId = input.string("trace_id", "");
        int limit = input.integer("limit", 30);
        return traceId == null || traceId.isBlank()
                ? queryByTimeWindow(input, limit)
                : queryByTrace(traceId, limit);
    }

    private ToolResult queryByTrace(String traceId, int limit) {
        // 精查键只由 traceId+limit 决定（时间字段置 0，历史日志不可变）
        String key = LogCacheKeys.logsKey(traceId, 0, 0, "", "", limit);
        List<Map<String, Object>> logs = cached(key, cacheProps.traceTtlMinutes() * 60_000L,
                () -> client.searchLogsByTraceId(traceId, limit));
        List<Map<String, Object>> view = new ArrayList<>();
        for (Map<String, Object> record : logs) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("time", isoTime(record.get("_timestamp")));
            line.put("level", nvl(levelOf(record)));
            line.put("service", nvl(firstOf(record, config().serviceFieldOrDefault(), "service")));
            line.put("logger", nvl(firstOf(record, "instrumentation_library_name", "logger", "logger_name")));
            line.put("msg", nvl(firstOf(record, config().messageFieldOrDefault(), "message", "msg", "log")));
            line.put("exception", nvl(firstOf(record, "exception_message", "exception",
                    "exceptionclass", "exception_class")));
            line.put("trace_id", nvl(firstOf(record, config().traceIdFieldOrDefault(), "traceid")));
            view.add(line);
        }
        String content = formatLogs("traceId=" + traceId, view);
        return ToolResult.ok(content, Map.of("logs", view));
    }

    private ToolResult queryByTimeWindow(ToolInput input, int limit) {
        String startRaw = orEmpty(input.string("start", ""));
        String endRaw = orEmpty(input.string("end", ""));
        long endMs = parseTimeOr(endRaw, System.currentTimeMillis());
        // start/end 均缺省 → 不限时间全量查：关键数据（流水号/orderNo）优先命中，
        // 时间只是用户明确给出时的过滤条件，不硬造「最近30分钟」把历史日志挡在窗外
        long startMs = startRaw.isBlank() && endRaw.isBlank()
                ? 0L
                : parseTimeOr(startRaw, endMs - DEFAULT_WINDOW_MS);
        String keyword = orEmpty(input.string("keyword", ""));
        String level = orEmpty(input.string("level", ""));

        List<String> conditions = new ArrayList<>();
        if (!keyword.isBlank()) {
            conditions.add(config().messageFieldOrDefault() + " LIKE '%"
                    + OpenObserveLogQueryClient.sqlEscape(keyword) + "%'");
        }
        if (!level.isBlank()) {
            conditions.add(config().levelFieldOrDefault() + " = '"
                    + OpenObserveLogQueryClient.sqlEscape(level.toUpperCase()) + "'");
        }
        // 时间窗键：start/end 落分钟桶（end=now 不平整会永远 miss；分钟桶与 60s TTL 对齐）
        String key = LogCacheKeys.logsKey("", LogCacheKeys.floorMinute(startMs),
                LogCacheKeys.floorMinute(endMs), keyword, level, limit);
        List<Map<String, Object>> logs = cached(key, cacheProps.windowTtlSeconds() * 1000L,
                () -> client.searchLogs(startMs, endMs, String.join(" AND ", conditions), limit));

        List<Map<String, Object>> view = new ArrayList<>();
        for (Map<String, Object> record : logs) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("time", String.valueOf(record.get("_timestamp")));
            line.put("level", nvl(firstOf(record, config().levelFieldOrDefault(), "level")));
            line.put("msg", truncate(nvl(firstOf(record, config().messageFieldOrDefault(),
                    "message", "msg", "log")), 200));
            line.put("trace_id", nvl(firstOf(record, config().traceIdFieldOrDefault(), "traceid")));
            view.add(line);
        }
        String scope = "start=" + Instant.ofEpochMilli(startMs) + ",end=" + Instant.ofEpochMilli(endMs)
                + (keyword.isBlank() ? "" : ",keyword=" + keyword)
                + (level.isBlank() ? "" : ",level=" + level);
        if (view.isEmpty()) {
            return ToolResult.ok("时间窗内无命中日志（" + scope + "）。"
                    + "建议：放宽时间窗、更换关键字，或向用户确认报错时间。", Map.of("logs", List.of()));
        }
        return ToolResult.ok(formatLogs(scope, view), Map.of("logs", view));
    }

    /**
     * 渲染日志视图（对齐参考 {@code OpsLogsSupport.formatLogs}：msg 160、exception 120）。
     *
     * @param scope 范围描述
     * @param logs  结构化视图
     * @return 文本
     */
    static String formatLogs(String scope, List<Map<String, Object>> logs) {
        if (logs.isEmpty()) {
            return "无命中日志（" + scope + "）";
        }
        StringBuilder sb = new StringBuilder("命中 ").append(logs.size()).append(" 条（").append(scope).append("）：\n");
        for (Map<String, Object> line : logs) {
            sb.append('[').append(line.getOrDefault("level", "?")).append("][")
                    .append(line.getOrDefault("time", "?")).append("] ")
                    .append(truncate(String.valueOf(line.getOrDefault("msg", "")), 160));
            Object ex = line.get("exception");
            if (ex != null && !String.valueOf(ex).isBlank()) {
                sb.append(" ⚠ ").append(truncate(String.valueOf(ex), 120));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * ISO-8601 解析，三级回退：带偏移量 → UTC（Z 结尾）→ **无时区的本地时间**。
     *
     * <p>第三级是真实联调补上的：模型常给出 {@code 2026-09-15T07:00:00} 这种不带时区的写法，
     * 前两级都会失败，若直接回退 fallback（now-30min）就会**静默查错时间窗**——
     * 表现为"明明给了时间却查不到日志"。无时区按系统默认时区解释。</p>
     *
     * @param value      时间字符串
     * @param fallbackMs 全部失败时的回退值
     * @return epoch 毫秒
     */
    static long parseTimeOr(String value, long fallbackMs) {
        if (value == null || value.isBlank()) {
            return fallbackMs;
        }
        String text = value.trim();
        try {
            return OffsetDateTime.parse(text).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            // fall through
        }
        try {
            return Instant.parse(text).toEpochMilli();
        } catch (Exception ignored) {
            // fall through
        }
        try {
            return java.time.LocalDateTime.parse(text)
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            // fall through
        }
        try {
            // 末级：仅日期（如 2026-09-15）按当天 00:00 解释
            return java.time.LocalDate.parse(text)
                    .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            return fallbackMs;
        }
    }

    /** 带过期读取：命中未过期直接返回，否则查库并写缓存（异常全吞，绝不阻断查询）。 */
    private List<Map<String, Object>> cached(String key, long ttlMillis,
            java.util.function.Supplier<List<Map<String, Object>>> loader) {
        if (!cacheProps.enabled()) {
            return loader.get();
        }
        long now = System.currentTimeMillis();
        TimedEntry entry = cache.get(key);
        if (entry != null && now < entry.expiresAt) {
            return entry.value;
        }
        List<Map<String, Object>> fresh = loader.get();
        try {
            cache.put(key, new TimedEntry(fresh, now + ttlMillis));
        } catch (Exception e) {
            log.debug("[query-logs] 缓存写入失败（忽略）：{}", e.getMessage());
        }
        return fresh;
    }

    private static Object firstOf(Map<String, Object> record, String... names) {
        for (String name : names) {
            Object value = record.get(name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private OpsProperties.OpenObserve config() {
        return fields == null
                ? new OpsProperties.OpenObserve(null, null, null, null, null, 0, 0, false, 0,
                        "body", "severity", "trace_id", "service_name", null)
                : fields;
    }

    private Object levelOf(Map<String, Object> record) {
        return firstOf(record, config().levelFieldOrDefault(), "severity", "level",
                "severity_text", "severitytext");
    }

    private static String isoTime(Object epochMicros) {
        if (epochMicros instanceof Number number) {
            // OpenObserve _timestamp 是微秒
            return Instant.ofEpochMilli(number.longValue() / 1000L).toString();
        }
        return numberToString(epochMicros);
    }

    private static String numberToString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String nvl(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value == null ? "" : value;
        }
        return value.substring(0, max) + "…";
    }

    /** 缓存值 + 过期时间。 */
    private record TimedEntry(List<Map<String, Object>> value, long expiresAt) {
    }
}
