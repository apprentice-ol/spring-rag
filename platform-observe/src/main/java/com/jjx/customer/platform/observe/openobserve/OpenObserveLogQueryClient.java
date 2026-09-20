package com.jjx.customer.platform.observe.openobserve;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenObserve 日志查询客户端（数据面）：{@code POST /api/{org}/_search}，
 * Basic auth，SQL-only（DataFusion），时间戳为 epoch 微秒。
 *
 * <p>两条查询路径：traceId 精查（{@code WHERE (trace_id='..' OR traceid='..')}
 * 回溯 lookbackDays）与时间窗模糊查（SQL LIMIT 与 body size 同值，钳制 [1,200]）。
 * hits 是扁平记录对象（无 _source 包装），逐条整体转 Map。失败语义：
 * HTTP 非 2xx / 超时 / 异常一律返回空列表并 warn，绝不向调用方抛。</p>
 *
 * <p>不做 Spring bean：端点配置由调用方实现 {@link OpenObserveEndpoint} 自带
 * （不同消费方配置源不同），构造后自行持有。</p>
 */
public class OpenObserveLogQueryClient {

    private static final Logger log = LoggerFactory.getLogger(OpenObserveLogQueryClient.class);

    /** 时间窗模糊查的 limit 硬上限。 */
    public static final int MAX_WINDOW_LIMIT = 200;

    private final OpenObserveEndpoint props;

    private final HttpClient http;

    private final ObjectMapper objectMapper;

    /**
     * @param props        OpenObserve 端点配置（调用方配置源）
     * @param objectMapper JSON 解析
     */
    public OpenObserveLogQueryClient(OpenObserveEndpoint props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** @return 是否可用（已配置 url + 账号 + 启用开关） */
    public boolean isAvailable() {
        return unavailableReason() == null;
    }

    /**
     * @return 不可用原因（可用时为 null）。把"未配置"拆成具体缺哪一项，
     *         否则排查时只看到一句笼统的"未配置"，无从下手。
     */
    public String unavailableReason() {
        if (props == null) {
            return "ops.openobserve 整段配置缺失";
        }
        if (!props.queryEnabled()) {
            return "ops.openobserve.query-enabled=false";
        }
        if (props.url() == null || props.url().isBlank()) {
            return "ops.openobserve.url 为空";
        }
        if (props.username() == null || props.username().isBlank()) {
            return "ops.openobserve.username 为空（检查 .env 的 OO_USERNAME 是否被读到）";
        }
        return null;
    }

    /**
     * traceId 精查：全链路日志按时间倒序。
     *
     * @param traceId 链路 id
     * @param limit   返回条数
     * @return 日志记录（扁平字段），失败返回空列表
     */
    public List<Map<String, Object>> searchLogsByTraceId(String traceId, int limit) {
        String stream = streamOf();
        // 服务排除紧跟主谓词（这里恒有 WHERE，直接 AND 即可）
        String exclusion = serviceExclusion();
        String sql = "SELECT * FROM \"" + stream + "\" WHERE (trace_id='" + sqlEscape(traceId)
                + "' OR traceid='" + sqlEscape(traceId) + "')"
                + (exclusion.isEmpty() ? "" : " AND " + exclusion)
                + " ORDER BY _timestamp DESC LIMIT " + Math.max(1, limit);
        long endMs = System.currentTimeMillis();
        long endMicros = endMs * 1000L;
        long startMicros = endMicros - props.lookbackDays() * 86_400_000_000L;
        // 精查时间窗是回溯窗口（lookbackDays），limit 写在 SQL 里，body 不带 from/size
        String body = "{\"query\":{\"sql\":" + jsonQuote(sql)
                + ",\"start_time\":" + startMicros + ",\"end_time\":" + endMicros + "}}";
        return search(body);
    }

    /**
     * 时间窗模糊查。
     *
     * @param startMs 开始时间（epoch 毫秒）
     * @param endMs   结束时间（epoch 毫秒）
     * @param whereSql 可选 WHERE 子句（不含 WHERE 关键字）
     * @param limit   返回条数（钳制 [1,200]，SQL LIMIT 与 body size 同值）
     * @return 日志记录（扁平字段），失败返回空列表
     */
    public List<Map<String, Object>> searchLogs(long startMs, long endMs, String whereSql, int limit) {
        int effective = Math.max(1, Math.min(limit, MAX_WINDOW_LIMIT));
        String where = whereSql == null || whereSql.isBlank() ? "" : " WHERE " + whereSql;
        String exclusion = serviceExclusion();
        if (!exclusion.isEmpty()) {
            // 调用方没给 WHERE 时排除谓词要自己起一个，否则 "FROM t AND ..." 是非法 SQL
            where = where.isEmpty() ? " WHERE " + exclusion : where + " AND " + exclusion;
        }
        String sql = "SELECT * FROM \"" + streamOf() + "\"" + where
                + " ORDER BY _timestamp DESC LIMIT " + effective;
        String body = "{\"query\":{\"sql\":" + jsonQuote(sql)
                + ",\"start_time\":" + startMs * 1000L
                + ",\"end_time\":" + endMs * 1000L
                + ",\"size\":" + effective + ",\"from\":0}}";
        return search(body);
    }

    /**
     * 服务排除谓词（空 = 不加）。
     *
     * <p>把平台自身的日志挡在查询之外——读回来会自我循环，见
     * {@link OpenObserveEndpoint#excludeServices()}。两条查询路径都得挂：
     * 报文/响应这类高影响槽是从 traceId 精查里挖出来的，漏挂一条等于没修。</p>
     */
    private String serviceExclusion() {
        return serviceExclusion(props.serviceField(), props.excludeServices());
    }

    /** 可单测的纯函数：拼 {@code field != 'a' AND field != 'b'}；无排除项返回空串。 */
    static String serviceExclusion(String field, String rawServices) {
        if (rawServices == null || rawServices.isBlank()) {
            return "";
        }
        String column = field == null || field.isBlank() ? "service_name" : field;
        return Arrays.stream(rawServices.split(","))
                .map(String::trim)
                .filter(name -> !name.isBlank())
                .map(name -> column + " != '" + sqlEscape(name) + "'")
                .collect(Collectors.joining(" AND "));
    }

    /**
     * 发起 _search 请求并解析 hits。
     *
     * @param body 请求体 JSON
     * @return 日志记录列表，失败返回空列表
     */
    private List<Map<String, Object>> search(String body) {
        if (!isAvailable()) {
            return List.of();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(props.url() + (props.url().endsWith("/") ? "" : "/") + "_search"))
                    .timeout(Duration.ofSeconds(props.timeoutSeconds() <= 0 ? 10 : props.timeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", basicAuth())
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("[openobserve] 查询失败 status={} body={}", response.statusCode(),
                        preview(response.body()));
                return List.of();
            }
            JsonNode root = objectMapper.readTree(response.body());
            // OpenObserve 的查询错误（列不存在、SQL 语法错等）返回 200 + {code,message}，
            // 不检查会静默变成"无命中"——排查时最误导人的失败模式，必须显式记录。
            JsonNode code = root.path("code");
            if (code.isNumber() && code.asInt() != 0) {
                log.warn("[openobserve] 查询被拒 code={} message={}", code.asInt(),
                        preview(root.path("message").asText()));
                return List.of();
            }
            JsonNode hits = root.path("hits");
            List<Map<String, Object>> out = new ArrayList<>();
            if (hits.isArray()) {
                for (JsonNode hit : hits) {
                    out.add(objectMapper.convertValue(hit, LinkedHashMap.class));
                }
            }
            return out;
        } catch (IOException e) {
            log.warn("[openobserve] 查询异常：{}", e.getMessage());
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    private String streamOf() {
        return props.stream() == null || props.stream().isBlank() ? "default" : props.stream();
    }

    private String basicAuth() {
        String credentials = props.username() + ":" + (props.password() == null ? "" : props.password());
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    /** SQL 单引号转义（单引号翻倍）。 */
    public static String sqlEscape(String value) {
        return value == null ? "" : value.replace("'", "''");
    }

    private String jsonQuote(String value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
    }

    private String preview(String body) {
        return body == null ? "" : (body.length() > 300 ? body.substring(0, 300) + "…" : body);
    }
}
