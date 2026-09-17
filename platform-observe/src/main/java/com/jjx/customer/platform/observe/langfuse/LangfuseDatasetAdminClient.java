package com.jjx.customer.platform.observe.langfuse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jjx.ai.llmobservability.backends.langfuse.LangfuseProperties;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Langfuse 数据集管理 client（自建 HTTP，补外部 jar 只有 list/link/score、无 create 的缺口）。
 * <p>API：{@code POST /api/public/datasets}（建数据集，幂等——已存在按成功处理）、
 * {@code POST /api/public/dataset-items}（建条目：input=问题、expectedOutput=标准答案、
 * metadata 带本地 itemId 与标准召回，供回查）。
 * <p>凭据复用 {@code telemetry.langfuse.*}（jar 的 LangfuseProperties 条件 bean，@ConditionalOnProperty
 * 默认开，未配 key 时 bean 不注册 → isAvailable()=false 天然降级，服务器部署零影响）。
 * 全操作抛异常由调用方（sink）软失败，绝不阻断跑批。
 */
@Slf4j
@Component
public class LangfuseDatasetAdminClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final ObjectProvider<LangfuseProperties> propsProvider;
    private final ObjectMapper objectMapper;
    private final OkHttpClient http;

    public LangfuseDatasetAdminClient(ObjectProvider<LangfuseProperties> propsProvider,
                                       ObjectMapper objectMapper,
                                       OkHttpClient syncHttpClient) {
        this.propsProvider = propsProvider;
        this.objectMapper = objectMapper;
        // 派生短超时 client（管理调用不该占长连接）
        this.http = syncHttpClient.newBuilder().callTimeout(15, TimeUnit.SECONDS).build();
    }

    /** 是否具备调用条件（凭据已配置）。 */
    public boolean isAvailable() {
        LangfuseProperties props = propsProvider.getIfAvailable();
        return props != null && props.hasApiCredentials();
    }

    /** 确保数据集存在（幂等：同名已存在的 400/409 按成功处理）。 */
    public void ensureDataset(String name) {
        ObjectNode body = objectMapper.createObjectNode().put("name", name);
        int status = post("/api/public/datasets", body.toString(), "ensureDataset");
        if (status >= 400 && !alreadyExists(status, null)) {
            throw new IllegalStateException("ensureDataset 失败: HTTP " + status);
        }
    }

    /**
     * 建数据集条目，返回 Langfuse 侧 datasetItemId。
     *
     * @param metadata 携带本地 itemId / 标准召回（docIds/docNames），供两侧对查
     */
    public String createDatasetItem(String datasetName, String input, String expectedOutput,
                                    Map<String, Object> metadata) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("datasetName", datasetName);
        body.put("input", input);
        if (expectedOutput != null && !expectedOutput.isBlank()) {
            body.put("expectedOutput", expectedOutput);
        }
        if (metadata != null && !metadata.isEmpty()) {
            body.set("metadata", objectMapper.valueToTree(metadata));
        }
        String responseBody = postForBody("/api/public/dataset-items", body.toString(), "createDatasetItem");
        try {
            return objectMapper.readTree(responseBody).path("id").asText(null);
        } catch (Exception e) {
            throw new IllegalStateException("createDatasetItem 响应解析失败: " + e.getMessage(), e);
        }
    }

    // ===== HTTP =====

    private String postForBody(String path, String json, String what) {
        Request request = new Request.Builder()
                .url(baseUrl() + path)
                .header("Authorization", authHeader())
                .post(RequestBody.create(json, JSON))
                .build();
        try (Response response = http.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IllegalStateException(what + " 失败: HTTP " + response.code()
                        + (alreadyExists(response.code(), responseBody) ? "（已存在）" : " body=" + preview(responseBody)));
            }
            return responseBody;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(what + " 异常: " + e.getMessage(), e);
        }
    }

    /**
     *  post请求
     * @param path 请求地址
     * @param json 请求参数
     * @param what
     * @return
     */
    private int post(String path, String json, String what) {
        Request request = new Request.Builder()
                .url(baseUrl() + path)
                .header("Authorization", authHeader())
                .post(RequestBody.create(json, JSON))
                .build();
        try (Response response = http.newCall(request).execute()) {
            return response.code();
        } catch (Exception e) {
            throw new IllegalStateException(what + " 异常: " + e.getMessage(), e);
        }
    }

    private static boolean alreadyExists(int status, String body) {
        // Langfuse 对重名数据集返回 400/409 且 body 含 "already exist"——幂等按成功处理
        return (status == 400 || status == 409)
                && (body == null || body.toLowerCase().contains("already exist"));
    }

    private String baseUrl() {
        return propsProvider.getIfAvailable().getUrl().replaceAll("/+$", "");
    }

    private String authHeader() {
        LangfuseProperties props = propsProvider.getIfAvailable();
        String auth = props.getAuth();
        if (auth != null && !auth.isBlank()) {
            return "Basic " + auth;
        }
        String token = Base64.getEncoder().encodeToString(
                (props.getPublicKey() + ":" + props.getSecretKey()).getBytes(StandardCharsets.UTF_8));
        return "Basic " + token;
    }

    private static String preview(String body) {
        return body != null && body.length() > 200 ? body.substring(0, 200) + "…" : String.valueOf(body);
    }
}
