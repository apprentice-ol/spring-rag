package com.jjx.ai.llmobservability.backends.langfuse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.ai.llmobservability.backends.langfuse.dto.*;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Langfuse 公共 API 客户端（读取/编排侧）。
 *
 * <p><b>边界</b>：只做“后端特有动作”的薄封装（数据集条目、run item 关联、评分写回、run 列表），
 * 不参与 OTel 采集热路径；供应用/脚本编排“问题 + 模型回答 vs 黄金答案”时使用。</p>
 *
 * <p><b>凭据</b>：优先使用 {@code telemetry.langfuse.auth}（base64(pk:sk)），否则用
 * {@code publicKey}/{@code secretKey} 拼接。凭据缺失时由自动装配跳过本 Bean。</p>
 *
 * <p><b>实现</b>：JDK HttpClient + Jackson，无额外依赖；所有非 2xx 与通信异常统一抛
 * {@link LangfuseApiException}，由调用方决定降级策略。</p>
 */
public class LangfuseApiClient implements LangfuseDatasetClient, LangfuseScoreClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_PAGE_SIZE = 50;

    private final HttpClient http;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String authHeader;

    /**
     * 构造 Langfuse API 客户端。
     *
     * @param properties Langfuse 连接配置（须已具备 API 凭据）
     * @throws IllegalArgumentException 未配置 API 凭据时抛出
     */
    public LangfuseApiClient(LangfuseProperties properties) {
        if (properties == null || !properties.hasApiCredentials()) {
            throw new IllegalArgumentException("Langfuse API 凭据未配置（需要 telemetry.langfuse.auth 或 publicKey/secretKey）");
        }
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.objectMapper = new ObjectMapper();
        this.baseUrl = normalizeBaseUrl(properties.getUrl()) + "/api/public";
        this.authHeader = buildAuthHeader(properties);
    }

    /**
     * 分页拉取数据集条目（黄金数据：input/expectedOutput）。
     *
     * @param datasetName 数据集名
     * @param limit       返回条数上限（大于 0）
     * @return 数据集条目列表（不会超过 limit）
     */
    public List<LangfuseDatasetItem> listDatasetItems(String datasetName, int limit) {
        int pageSize = Math.max(1, Math.min(limit, MAX_PAGE_SIZE));
        int page = 1;
        List<LangfuseDatasetItem> items = new ArrayList<>();
        while (items.size() < limit) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("datasetName", datasetName);
            params.put("page", String.valueOf(page));
            params.put("limit", String.valueOf(pageSize));
            JsonNode response = get("/dataset-items", params);
            JsonNode data = response.path("data");
            if (!data.isArray()) {
                break;
            }
            for (JsonNode node : data) {
                items.add(toObject(node, LangfuseDatasetItem.class));
            }
            int total = response.path("meta").path("totalItems").asInt(items.size());
            if (data.isEmpty() || items.size() >= total || items.size() >= limit) {
                break;
            }
            page++;
        }
        if (items.size() > limit) {
            return new ArrayList<>(items.subList(0, limit));
        }
        return items;
    }

    /**
     * 把黄金数据条目与模型运行记录关联到数据集 run。
     *
     * @param link 关联请求（runName + datasetItemId + traceId + observationId）
     * @return 创建/更新后的 run item
     */
    public LangfuseRunItem linkRunItem(LangfuseRunItemLink link) {
        JsonNode response = post("/dataset-run-items", link);
        return toObject(response, LangfuseRunItem.class);
    }

    /**
     * 提交一条评分（写回数据集 run / trace）。
     *
     * @param submission 评分内容
     * @return 创建的评分 id
     */
    public String submitScore(LangfuseScoreSubmission submission) {
        JsonNode response = post("/scores", submission);
        return response.path("id").asText(null);
    }

    /**
     * 列出指定数据集的 run（实验）。
     *
     * @param datasetName 数据集名
     * @param limit       返回条数上限（大于 0）
     * @return run 列表
     */
    public List<LangfuseDatasetRun> listRuns(String datasetName, int limit) {
        int pageSize = Math.max(1, Math.min(limit, MAX_PAGE_SIZE));
        Map<String, String> params = new LinkedHashMap<>();
        params.put("page", "1");
        params.put("limit", String.valueOf(pageSize));
        JsonNode response = get("/datasets/" + encode(datasetName) + "/runs", params);
        List<LangfuseDatasetRun> runs = new ArrayList<>();
        JsonNode data = response.path("data");
        if (!data.isArray()) {
            return runs;
        }
        for (JsonNode node : data) {
            runs.add(toObject(node, LangfuseDatasetRun.class));
            if (runs.size() >= limit) {
                break;
            }
        }
        return runs;
    }

    private JsonNode get(String path, Map<String, String> params) {
        String url = baseUrl + path + buildQuery(params);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .header("Authorization", authHeader)
                .GET()
                .build();
        return send(request);
    }

    private JsonNode post(String path, Object body) {
        String url = baseUrl + path;
        String json = writeJson(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(TIMEOUT)
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return send(request);
    }

    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int statusCode = response.statusCode();
            if (statusCode < 200 || statusCode >= 300) {
                String message = "Langfuse API 调用失败: " + request.method() + " " + request.uri();
                throw new LangfuseApiException(message, statusCode, response.body());
            }
            return objectMapper.readTree(response.body());
        } catch (LangfuseApiException e) {
            throw e;
        } catch (IOException e) {
            throw new LangfuseApiException("Langfuse API 通信异常: " + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LangfuseApiException("Langfuse API 调用被中断: " + request.uri(), e);
        }
    }

    private <T> T toObject(JsonNode node, Class<T> type) {
        try {
            return objectMapper.treeToValue(node, type);
        } catch (java.io.IOException e) {
            throw new LangfuseApiException("Langfuse API 响应解析失败: " + type.getSimpleName(), e);
        }
    }

    private String writeJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (java.io.IOException e) {
            throw new LangfuseApiException("Langfuse API 请求序列化失败", e);
        }
    }

    private String buildQuery(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        StringBuilder query = new StringBuilder("?");
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (query.length() > 1) {
                query.append("&");
            }
            query.append(entry.getKey()).append("=").append(encode(entry.getValue()));
        }
        return query.toString();
    }

    private String encode(String value) {
        if (value == null) {
            value = "";
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String buildAuthHeader(LangfuseProperties properties) {
        String auth = properties.getAuth();
        if (hasText(auth)) {
            return "Basic " + auth;
        }
        String raw = properties.getPublicKey() + ":" + properties.getSecretKey();
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private String normalizeBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String trimmed = url.trim();
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
