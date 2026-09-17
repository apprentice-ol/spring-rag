package com.jjx.customer.platform.observe.tools;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.ai.llmobservability.backends.openobserve.OpenObserveQueryClient;
import com.jjx.ai.llmobservability.backends.openobserve.dto.TraceLogEntry;
import com.jjx.customer.platform.agent.framework.tool.ExtensionTool;
import com.jjx.customer.platform.agent.framework.tool.ToolInvocation;
import com.jjx.customer.platform.agent.framework.tool.ToolResult;
import com.jjx.customer.platform.cache.LogCacheKeys;
import com.jjx.customer.platform.cache.CacheProperties;
import com.jjx.customer.platform.cache.CacheStore;
import com.jjx.customer.platform.common.util.TextPreviews;
import com.jjx.customer.platform.observe.openobserve.OpenObserveSearchClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行日志查询扩展工具（T1）：traceId 精查（长 TTL）/ 时间窗模糊查（key 落分钟桶，短 TTL）。
 *
 * <p>能力面工具：只产出数据与结构化明细，不改变循环走向。</p>
 */
@Slf4j
@Component
public class OpsQueryLogsTool implements ExtensionTool {

    public static final String NAME = "query_logs";

    private final ObjectProvider<OpenObserveQueryClient> byTraceClientProvider;
    private final OpenObserveSearchClient searchClient;
    private final CacheStore cacheStore;
    private final CacheProperties cacheProperties;
    private final ObjectMapper objectMapper;

    public OpsQueryLogsTool(ObjectProvider<OpenObserveQueryClient> byTraceClientProvider,
                            OpenObserveSearchClient searchClient,
                            CacheStore cacheStore,
                            CacheProperties cacheProperties,
                            ObjectMapper objectMapper) {
        this.byTraceClientProvider = byTraceClientProvider;
        this.searchClient = searchClient;
        this.cacheStore = cacheStore;
        this.cacheProperties = cacheProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "查询服务日志排障。两种方式：① 有 traceId 时按 traceId 精查全链路日志；"
                + "② 按时间窗（start/end，ISO-8601）加可选关键字/级别模糊查；时间窗缺省查最近 30 分钟。";
    }

    @Override
    public String inputSchema() {
        return """
                {"type":"object","properties":{
                  "trace_id":{"type":"string","description":"链路追踪ID，提供则走精查"},
                  "start":{"type":"string","description":"开始时间 ISO-8601"},
                  "end":{"type":"string","description":"结束时间 ISO-8601，缺省=now"},
                  "keyword":{"type":"string","description":"日志关键字"},
                  "level":{"type":"string","description":"日志级别，如 ERROR"},
                  "limit":{"type":"integer","description":"返回条数上限，默认 30"}
                }}""";
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        String traceId = invocation.str("trace_id", "");
        int limit = invocation.intOr("limit", 30);
        return traceId.isBlank() ? queryByTimeWindow(invocation, limit) : queryByTrace(traceId, limit);
    }

    private ToolResult queryByTrace(String traceId, int limit) {
        boolean cacheOn = cacheProperties.isEnabled() && cacheProperties.getLogs().isEnabled();
        String key = LogCacheKeys.logsKey(traceId, 0, 0, "", "", limit);
        if (cacheOn) {
            CachedLogs hit = readCache(key);
            if (hit != null) {
                return ToolResult.ok(hit.content(), List.of(), Map.of("logs", hit.view(), "cached", true));
            }
        }
        OpenObserveQueryClient client = byTraceClientProvider.getIfAvailable();
        if (client == null) {
            return ToolResult.error("日志查询服务未启用（telemetry.openobserve.query-enabled=false）");
        }
        List<TraceLogEntry> logs = client.searchLogsByTraceId(traceId, limit);
        List<Map<String, Object>> view = new ArrayList<>();
        for (TraceLogEntry l : logs) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("time", Instant.ofEpochMilli(l.timestamp()).toString());
            row.put("level", OpsLogsSupport.nvl(l.level()));
            row.put("logger", OpsLogsSupport.nvl(l.logger()));
            row.put("msg", OpsLogsSupport.nvl(l.message()));
            row.put("exception", OpsLogsSupport.nvl(l.exception()));
            view.add(row);
        }
        ToolResult ok = ToolResult.ok(OpsLogsSupport.formatLogs("traceId=" + traceId, view), List.of(),
                Map.of("logs", view));
        if (cacheOn) {
            writeCache(key, new CachedLogs(ok.content(), view), cacheProperties.getLogsTraceTtl());
        }
        return ok;
    }

    private ToolResult queryByTimeWindow(ToolInvocation invocation, int limit) {
        if (!searchClient.isAvailable()) {
            return ToolResult.error("日志查询服务未配置（telemetry.openobserve.*），无法按时间窗查询");
        }
        long endMs = OpsLogsSupport.parseTimeOr(invocation.str("end", ""), System.currentTimeMillis());
        long startMs = OpsLogsSupport.parseTimeOr(invocation.str("start", ""), endMs - 30 * 60 * 1000L);
        String keyword = invocation.str("keyword", "");
        String level = invocation.str("level", "");
        boolean cacheOn = cacheProperties.isEnabled() && cacheProperties.getLogs().isEnabled();
        String key = LogCacheKeys.logsKey("", OpsLogsSupport.floorMinute(startMs), OpsLogsSupport.floorMinute(endMs),
                keyword, level, limit);
        if (cacheOn) {
            CachedLogs hit = readCache(key);
            if (hit != null) {
                return ToolResult.ok(hit.content(), List.of(), Map.of("logs", hit.view(), "cached", true));
            }
        }
        List<String> conditions = new ArrayList<>();
        // OTel 规范化后正文/级别落在 body/severity（8/14 之前的历史数据在 message/level）：
        // 只查一套列会在另一套上静默命中 0 条，这里两套都带上。
        if (!keyword.isBlank()) {
            String kw = OpenObserveSearchClient.sqlEscape(keyword);
            conditions.add("(body LIKE '%" + kw + "%' OR message LIKE '%" + kw + "%')");
        }
        if (!level.isBlank()) {
            String lv = OpenObserveSearchClient.sqlEscape(level.toUpperCase());
            conditions.add("(severity = '" + lv + "' OR level = '" + lv + "')");
        }
        List<Map<String, Object>> logs = searchClient.searchLogs(startMs, endMs,
                String.join(" AND ", conditions), limit);
        List<Map<String, Object>> slim = new ArrayList<>();
        for (Map<String, Object> l : logs) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("time", String.valueOf(l.get("_timestamp")));
            row.put("level", OpsLogsSupport.firstNonBlank(l.get("severity"), l.get("level")));
            row.put("msg", TextPreviews.truncate(
                    OpsLogsSupport.firstNonBlank(l.get("body"), l.get("message")), 200));
            row.put("trace_id", OpsLogsSupport.firstNonBlank(l.get("trace_id"), l.get("traceid")));
            slim.add(row);
        }
        String scope = OpsLogsSupport.formatInstant(startMs) + " ~ " + OpsLogsSupport.formatInstant(endMs);
        ToolResult ok = logs.isEmpty()
                ? ToolResult.ok("时间窗内无命中日志（" + scope + "）。建议：放宽时间窗、更换关键字，或向用户确认报错时间。",
                        List.of(), Map.of("logs", List.of()))
                : ToolResult.ok(OpsLogsSupport.formatLogs(scope, slim), List.of(), Map.of("logs", slim));
        if (cacheOn) {
            writeCache(key, new CachedLogs(ok.content(), slim), cacheProperties.getLogs().getTtl());
        }
        return ok;
    }

    // ===== 缓存 helpers（全吞异常：缓存绝不阻断日志查询） =====

    record CachedLogs(String content, List<Map<String, Object>> view) {
    }

    @SuppressWarnings("unchecked")
    private CachedLogs readCache(String key) {
        try {
            String json = cacheStore.get(key, "logs").orElse(null);
            if (json == null) {
                return null;
            }
            return objectMapper.readValue(json, CachedLogs.class);
        } catch (Exception e) {
            log.debug("[query_logs] 读缓存失败（忽略）: {}", e.getMessage());
            return null;
        }
    }

    private void writeCache(String key, CachedLogs payload, java.time.Duration ttl) {
        try {
            cacheStore.put(key, objectMapper.writeValueAsString(payload), ttl, "logs");
        } catch (Exception e) {
            log.debug("[query_logs] 写缓存失败（忽略）: {}", e.getMessage());
        }
    }
}
