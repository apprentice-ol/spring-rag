package com.jjx.ai.llmobservability.backends.openobserve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jjx.ai.llmobservability.backends.openobserve.dto.TraceLogEntry;
import com.jjx.ai.llmobservability.backends.openobserve.dto.TraceSpan;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * OpenObserve 查询客户端（读取侧）。
 *
 * <p>按 traceId 查询日志（{@code springai_rag_logs} stream）与 span（{@code default} trace stream），
 * POST SQL 到 {@code /api/default/_search}。认证、端点、stream 名复用 {@link OpenObserveProperties}。</p>
 *
 * <p><b>边界</b>：只做读取查询，不参与采集热路径；查询失败/超时不抛异常，
 * 返回空列表 + warn（诊断等业务不阻断）。</p>
 *
 * <p><b>兼容</b>：OTLP Logs 进入 OpenObserve 后字段通常为 {@code trace_id}/{@code span_id}；
 * 为兼容旧版自定义 appender，查询和解析同时保留 {@code traceid}/{@code spanid} 等旧字段。</p>
 */
@Slf4j
public class OpenObserveQueryClient {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** span 记录中已单独映射、不再进 attributes 的字段。 */
    private static final Set<String> KNOWN_SPAN_FIELDS = Set.of(
            "trace_id", "span_id", "parent_span_id", "name", "service_name",
            "operation_name", "start_time", "end_time", "duration",
            "status", "status_code", "_timestamp", "timestamp", "traceid",
            "spanid", "parentspanid", "service");

    private final OpenObserveProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 构造 OpenObserve 查询客户端。
     *
     * @param properties   OpenObserve 连接配置
     * @param objectMapper JSON 解析器
     */
    public OpenObserveQueryClient(OpenObserveProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 按 traceId 查日志，按时间倒序（最近优先）。
     *
     * @param traceId 链路 ID（自动清洗为 [A-Za-z0-9-]，防 SQL 注入）
     * @param limit   返回上限
     * @return 日志条目列表；查询失败时返回空列表
     */
    public List<TraceLogEntry> searchLogsByTraceId(String traceId, int limit) {
        String safe = sanitizeId(traceId);
        if (safe.isBlank()) {
            return List.of();
        }
        String sql = "SELECT * FROM \"" + properties.getStream() + "\" WHERE "
                + "(trace_id='" + safe + "' OR traceid='" + safe + "')"
                + " ORDER BY _timestamp DESC LIMIT " + Math.max(1, limit);
        JsonNode response = query(sql, null);
        if (response == null) {
            return List.of();
        }
        return parseLogHits(response);
    }

    /**
     * 按 traceId 查 span 链路，按开始时间正序。
     *
     * @param traceId 链路 ID（自动清洗，防 SQL 注入）
     * @param limit   返回上限
     * @return span 列表；查询失败时返回空列表
     */
    public List<TraceSpan> searchSpansByTraceId(String traceId, int limit) {
        String safe = sanitizeId(traceId);
        if (safe.isBlank()) {
            return List.of();
        }
        String sql = "SELECT * FROM \"" + properties.getTraceStream() + "\" WHERE "
                + "trace_id='" + safe + "' ORDER BY start_time ASC LIMIT " + Math.max(1, limit);
        JsonNode response = query(sql, "traces");
        if (response == null) {
            return List.of();
        }
        return parseSpanHits(response);
    }

    /**
     * 执行一次 SQL 查询。
     *
     * @param sql  查询 SQL
     * @param type 流类型（logs/traces/metrics）；null 时按 OpenObserve 默认处理
     * @return 响应 JSON；请求失败时返回 null
     */
    private JsonNode query(String sql, String type) {
        long nowUs = System.currentTimeMillis() * 1000L;
        long startUs = nowUs - (long) properties.getLookbackDays() * 86_400_000_000L;
        try {
            ObjectNode queryNode = objectMapper.createObjectNode();
            queryNode.put("sql", sql);
            queryNode.put("start_time", startUs);
            queryNode.put("end_time", nowUs);
            ObjectNode body = objectMapper.createObjectNode();
            body.set("query", queryNode);
            String bodyJson = objectMapper.writeValueAsString(body);

            String url = properties.getUrl() + "/_search";
            if (hasText(type)) {
                url = url + "?type=" + type;
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Authorization", basicAuth())
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("[OpenObserve] 查询失败 status={} body={}", response.statusCode(), response.body());
                return null;
            }
            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            log.warn("[OpenObserve] 查询异常 sql={}: {}", sql, e.getMessage());
            return null;
        }
    }

    private List<TraceLogEntry> parseLogHits(JsonNode root) {
        JsonNode hits = root.path("hits");
        if (!hits.isArray()) {
            return List.of();
        }
        List<TraceLogEntry> out = new ArrayList<>(hits.size());
        for (JsonNode hit : hits) {
            JsonNode source = hit;
            if (hit.has("_source")) {
                source = hit.path("_source");
            }
            out.add(new TraceLogEntry(
                    source.path("_timestamp").asLong(0),
                    firstText(source, "severity_text", "severitytext", "level"),
                    firstText(source, "logger_name", "log.logger", "logger"),
                    firstText(source, "thread_name", "log.thread.name", "thread"),
                    firstText(source, "body", "message"),
                    firstText(source, "trace_id", "traceid"),
                    firstText(source, "span_id", "spanid"),
                    firstNullable(source, "exception_type", "exception_type_name", "exceptionClass"),
                    firstNullable(source, "exception_stacktrace", "exception", "exception.message")
            ));
        }
        return out;
    }

    private List<TraceSpan> parseSpanHits(JsonNode root) {
        JsonNode hits = root.path("hits");
        if (!hits.isArray()) {
            return List.of();
        }
        List<TraceSpan> out = new ArrayList<>(hits.size());
        for (JsonNode hit : hits) {
            JsonNode source = hit;
            if (hit.has("_source")) {
                source = hit.path("_source");
            }
            out.add(toSpan(source));
        }
        return out;
    }

    private TraceSpan toSpan(JsonNode source) {
        long startUs = source.path("start_time").asLong(0);
        long endUs = source.path("end_time").asLong(0);
        long durationUs = source.path("duration").asLong(endUs - startUs);
        Map<String, Object> attributes = new LinkedHashMap<>();
        source.properties().forEach(entry -> {
            if (!KNOWN_SPAN_FIELDS.contains(entry.getKey())) {
                attributes.put(entry.getKey(), objectMapper.convertValue(entry.getValue(), Object.class));
            }
        });
        return new TraceSpan(
                firstText(source, "span_id", "spanid"),
                firstText(source, "trace_id", "traceid"),
                firstText(source, "parent_span_id", "parentspanid"),
                firstText(source, "name"),
                firstText(source, "service_name", "service"),
                firstText(source, "operation_name"),
                startUs,
                endUs,
                durationUs,
                firstText(source, "status", "status_code"),
                attributes
        );
    }

    private String sanitizeId(String traceId) {
        if (traceId == null) {
            return "";
        }
        return traceId.replaceAll("[^A-Za-z0-9-]", "");
    }

    private String firstText(JsonNode source, String... fields) {
        for (String field : fields) {
            JsonNode node = source.get(field);
            if (node != null && !node.isNull()) {
                String value = textValue(node);
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return "";
    }

    private String firstNullable(JsonNode source, String... fields) {
        for (String field : fields) {
            JsonNode node = source.get(field);
            if (node != null && !node.isNull()) {
                String value = node.asText("");
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    private String textValue(JsonNode node) {
        if (node.isObject() && node.has("stringValue")) {
            return node.path("stringValue").asText("");
        }
        return node.asText("");
    }

    private String basicAuth() {
        String raw = properties.getUsername() + ":" + properties.getPassword();
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
