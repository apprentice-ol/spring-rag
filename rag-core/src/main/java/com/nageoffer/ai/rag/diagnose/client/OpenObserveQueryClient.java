package com.nageoffer.ai.rag.diagnose.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nageoffer.ai.obs.backends.openobserve.OpenObserveProperties;
import com.nageoffer.ai.rag.diagnose.dto.TraceLogEntry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * OpenObserve 日志查询 Client（诊断「读取侧」）。
 *
 * <p>按 trace_id 查询 {@code springai_rag_logs} stream，POST SQL 到 {@code /api/default/_search}。
 * 认证、端点、stream 名复用 {@link OpenObserveProperties}。
 *
 * <p>OTLP Logs 进入 OpenObserve 后字段通常为 {@code trace_id}/{@code span_id}；
 * 为兼容旧版自定义 appender，查询和解析同时保留 {@code traceid}/{@code spanid} 等旧字段。
 *
 * <p>时间戳坑：{@code start_time}/{@code end_time} 必须是 i64 数字（微秒），用
 * {@link ObjectNode#put(String, long)} 构造保证，不能写成字符串。
 *
 * <p>查询失败/超时不抛异常，返回空列表 + warn（诊断不阻断，suggestion 仍可基于已有信息给）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenObserveQueryClient {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final OpenObserveProperties props;
    private final ObjectMapper objectMapper;

    /**
     * 按 traceId 查日志，按时间倒序（最近优先）。
     *
     * @param traceId 链路 ID（自动清洗为 [A-Za-z0-9-]，防 SQL 注入）
     * @param limit   返回上限
     */
    public List<TraceLogEntry> searchByTraceId(String traceId, int limit) {
        String safe = traceId == null ? "" : traceId.replaceAll("[^A-Za-z0-9-]", "");
        if (safe.isBlank()) {
            return List.of();
        }
        long nowUs = System.currentTimeMillis() * 1000L;
        long startUs = now_us(props.getLookbackDays(), nowUs);
        try {
            String sql = "SELECT * FROM \"" + props.getStream() + "\" WHERE "
                    + "(trace_id='" + safe + "' OR traceid='" + safe + "')"
                    + " ORDER BY _timestamp DESC LIMIT " + Math.max(1, limit);

            ObjectNode query = objectMapper.createObjectNode();
            query.put("sql", sql);
            query.put("start_time", startUs);
            query.put("end_time", nowUs);
            ObjectNode body = objectMapper.createObjectNode();
            body.set("query", query);
            String bodyJson = objectMapper.writeValueAsString(body);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(props.getUrl() + "/_search"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Authorization", basicAuth())
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                log.warn("[OpenObserve] 查询失败 status={} body={}", resp.statusCode(), resp.body());
                return List.of();
            }
            return parseHits(resp.body());
        } catch (Exception e) {
            log.warn("[OpenObserve] 查询异常 traceId={}: {}", traceId, e.getMessage());
            return List.of();
        }
    }

    private static long now_us(int lookbackDays, long nowUs) {
        return nowUs - (long) lookbackDays * 86_400_000_000L;
    }

    private List<TraceLogEntry> parseHits(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        JsonNode hits = root.path("hits");
        if (!hits.isArray()) {
            return List.of();
        }
        List<TraceLogEntry> out = new ArrayList<>(hits.size());
        for (JsonNode h : hits) {
            JsonNode s = h.has("_source") ? h.path("_source") : h;
            out.add(new TraceLogEntry(
                    s.path("_timestamp").asLong(0),
                    firstText(s, "severity_text", "severitytext", "level"),
                    firstText(s, "logger_name", "log.logger", "logger"),
                    firstText(s, "thread_name", "log.thread.name", "thread"),
                    firstText(s, "body", "message"),
                    firstText(s, "trace_id", "traceid"),
                    firstText(s, "span_id", "spanid"),
                    firstNullable(s, "exception_type", "exception_type_name", "exceptionClass"),
                    firstNullable(s, "exception_stacktrace", "exception", "exception.message")
            ));
        }
        return out;
    }

    private static String firstText(JsonNode src, String... fields) {
        for (String field : fields) {
            JsonNode n = src.get(field);
            if (n != null && !n.isNull()) {
                String value = textValue(n);
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return "";
    }

    private static String textValue(JsonNode n) {
        if (n.isObject() && n.has("stringValue")) {
            return n.path("stringValue").asText("");
        }
        return n.asText("");
    }

    /** 异常类字段缺失返回 null（区别于空串，便于上层判断"有无报错"） */
    private static String firstNullable(JsonNode src, String... fields) {
        for (String field : fields) {
            JsonNode n = src.get(field);
            if (n != null && !n.isNull()) {
                String v = n.asText("");
                if (!v.isBlank()) {
                    return v;
                }
            }
        }
        return null;
    }

    private String basicAuth() {
        String raw = props.getUsername() + ":" + props.getPassword();
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
