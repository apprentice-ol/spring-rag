package com.jjx.customer.platform.observe.openobserve;

import jakarta.annotation.PostConstruct;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.ai.llmobservability.backends.openobserve.OpenObserveProperties;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * OpenObserve 任意条件日志查询 client（HTTP API {@code POST {url}/_search}）。
 * <p>补齐外部 jar 里 {@code OpenObserveQueryClient} 只支持按 traceId 查询的短板：
 * 支持时间窗 + SQL WHERE 条件（关键字/级别），供 agent 的 query_logs 工具使用。
 * <p>时间参数为微秒时间戳（OpenObserve 惯例）；查询流默认 {@code telemetry.openobserve.stream}。
 * 查询失败返回空列表（不阻断 agent——错误文本由工具层回喂模型）。
 */
@Slf4j
@Component
public class OpenObserveSearchClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OpenObserveProperties props;
    private final ObjectMapper objectMapper;
    private final OkHttpClient http;

    public OpenObserveSearchClient(ObjectProvider<OpenObserveProperties> propsProvider,
                                   ObjectMapper objectMapper) {
        this.props = propsProvider.getIfAvailable();
        this.objectMapper = objectMapper;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 启动即打印读取侧实际生效的地址/流/账号（密码只报是否配置）：
     * 之前查不到日志的根因就是 .env 未生效、读取侧退回 admin@dev.local 占位账号导致 401，
     * 而写链路走 collector，两者不一致时从日志表面看不出来。
     */
    @PostConstruct
    void logResolvedConfig() {
        if (props == null) {
            log.warn("[OOSearch] 未装配 telemetry.openobserve.*，日志查询不可用");
            return;
        }
        boolean passwordSet = props.getPassword() != null && !props.getPassword().isBlank();
        log.info("[OOSearch] url={} stream={} user={} passwordSet={} queryEnabled={}",
                props.getUrl(), props.getStream(), props.getUsername(), passwordSet, props.isQueryEnabled());
        if (props.getUsername() != null && props.getUsername().endsWith("@dev.local")) {
            log.warn("[OOSearch] 读取侧仍是占位账号 {}：.env 的 OO_USERNAME/OO_PASSWORD 未生效，"
                    + "查询会 401（写链路走 collector 不受影响）", props.getUsername());
        }
    }

    /** 是否具备查询条件（未配置 OO 或关闭 query 时不具备）。 */
    public boolean isAvailable() {
        return props != null && props.isQueryEnabled() && props.getUrl() != null && !props.getUrl().isBlank()
                && props.getUsername() != null && !props.getUsername().isBlank();
    }

    /**
     * 时间窗 + 条件查询日志。
     *
     * @param startMs   开始时间（毫秒时间戳）
     * @param endMs     结束时间（毫秒时间戳）
     * @param whereSql  额外 WHERE 条件（不含 WHERE 关键字，可为空；值必须已转义）
     * @param limit     返回条数上限
     */
    public List<Map<String, Object>> searchLogs(long startMs, long endMs, String whereSql, int limit) {
        if (!isAvailable()) {
            return List.of();
        }
        String stream = (props.getStream() == null || props.getStream().isBlank())
                ? "default" : props.getStream();
        String sql = "SELECT * FROM \"" + stream + "\""
                + (whereSql == null || whereSql.isBlank() ? "" : " WHERE " + whereSql)
                + " ORDER BY _timestamp DESC LIMIT " + Math.max(1, Math.min(limit, 200));
        String body = """
                {"query":{"sql":%s,"start_time":%d,"end_time":%d,"size":%d,"from":0}}""".formatted(
                jsonQuote(sql), startMs * 1000L, endMs * 1000L, Math.max(1, Math.min(limit, 200)));

        Request request = new Request.Builder()
                .url(props.getUrl() + (props.getUrl().endsWith("/") ? "" : "/") + "_search")
                .header("Authorization", basicAuth())
                .post(RequestBody.create(body, JSON))
                .build();
        try (Response resp = http.newCall(request).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                log.warn("[OOSearch] 查询失败: HTTP {}（url={} user={}）",
                        resp.code(), props.getUrl(), props.getUsername());
                if (resp.code() == 401) {
                    log.warn("[OOSearch] 401=OpenObserve 不接受读取侧凭据：确认 .env 的 "
                            + "OO_USERNAME/OO_PASSWORD 已被加载，且与写入日志的实例同源");
                }
                return List.of();
            }
            JsonNode hits = objectMapper.readTree(resp.body().string()).path("hits");
            List<Map<String, Object>> out = new ArrayList<>();
            if (hits.isArray()) {
                for (JsonNode h : hits) {
                    Map<String, Object> m = objectMapper.convertValue(h, Map.class);
                    out.add(new LinkedHashMap<>(m));
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("[OOSearch] 查询异常: {}", e.getMessage());
            return List.of();
        }
    }

    /** SQL 字符串字面量转义（单引号翻倍）。 */
    public static String sqlEscape(String v) {
        return v == null ? "" : v.replace("'", "''");
    }

    private String basicAuth() {
        String token = Base64.getEncoder().encodeToString(
                (props.getUsername() + ":" + props.getPassword()).getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
    }

    private String jsonQuote(String s) {
        try {
            return objectMapper.writeValueAsString(s);
        } catch (Exception e) {
            return "\"" + s.replace("\"", "\\\"") + "\"";
        }
    }
}
