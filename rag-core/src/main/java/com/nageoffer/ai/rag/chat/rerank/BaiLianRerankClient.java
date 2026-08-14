package com.nageoffer.ai.rag.chat.rerank;

import cn.hutool.core.collection.CollUtil;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nageoffer.ai.rag.chat.retrieval.RetrievedChunk;
import com.nageoffer.ai.rag.chat.retrieval.SearchChannelType;
import com.nageoffer.ai.obs.StructuredLog;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import com.nageoffer.ai.obs.TraceStep;

/**
 * 百炼 Rerank 客户端。
 * <p>
 * 调用阿里云百炼的 Rerank API 对检索结果进行精排。
 * 需要配置 rag.rerank.bailian.api-key 和 rag.rerank.bailian.base-url。
 * 当 rag.rerank.enabled=true 时启用。
 * </p>
 *
 * <p>API 参考：https://help.aliyun.com/zh/model-studio/developer-reference/rag</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "rag.rerank.enabled", havingValue = "true", matchIfMissing = false)
public class BaiLianRerankClient implements RerankClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final Gson gson;

    @Value("${rag.rerank.bailian.base-url:https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank}")
    private String baseUrl;

    @Value("${rag.rerank.bailian.api-key:}")
    private String apiKey;

    @Value("${rag.rerank.bailian.model:qwen3-rerank}")
    private String model;

    /** Rerank 相关度下限：低于此分的块不进上下文（0=关闭）。仅对 API 返回的真实 relevance_score 生效，fallback 不参与 */
    @Value("${rag.rerank.min-relevance-score:0.0}")
    private double minRelevanceScore;

    public BaiLianRerankClient(OkHttpClient httpClient, Gson gson) {
        this.httpClient = httpClient;
        this.gson = gson;
    }

    @Override
    public String provider() {
        return "bailian";
    }

    @Override
    @TraceStep("rag.rerank.call")
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN) {
        if (CollUtil.isEmpty(candidates) || topN <= 0) {
            return List.of();
        }

        // 先按 id 去重
        List<RetrievedChunk> deduped = dedupById(candidates);

        if (deduped.isEmpty()) {
            return List.of();
        }

        // 候选不足 topN 时仍需走精排打分：min-relevance-score 相关度下限过滤依赖 API 真实分数，
        // 直接返回会让弱相关块（如无标点长查询下关键词通道整篇文档路由带进来的块）未过滤即进 LLM 上下文，
        // 且顺序退化为融合序（关键词通道同分 → chunk_index 序，可能把次相关块排前）。
        return doRerank(query, deduped, Math.min(topN, deduped.size()));
    }

    private List<RetrievedChunk> doRerank(String query, List<RetrievedChunk> candidates, int topN) {
        JsonObject reqBody = new JsonObject();
        reqBody.addProperty("model", model);

        JsonObject input = new JsonObject();
        input.addProperty("query", query);

        JsonArray documents = new JsonArray();
        for (RetrievedChunk c : candidates) {
            documents.add(c.getContent() != null ? c.getContent() : "");
        }
        input.add("documents", documents);

        JsonObject params = new JsonObject();
        params.addProperty("top_n", topN);
        params.addProperty("return_documents", true);

        reqBody.add("input", input);
        reqBody.add("parameters", params);

        // 官方端点要求双段 .../services/rerank/text-rerank/text-rerank（2026 新版）。
        // 兼容配置里已含单段/双段的情况：缺哪段补哪段，避免配置笔误让 rerank 静默失效（400 退化回向量序）。
        String base = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        String url;
        if (base.endsWith("/text-rerank/text-rerank")) {
            url = base;
        } else if (base.endsWith("/text-rerank")) {
            url = base + "/text-rerank";
        } else {
            url = base + "/text-rerank/text-rerank";
        }
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(gson.toJson(reqBody), JSON))
                .addHeader("Authorization", "Bearer " + apiKey)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "";
                log.warn("[BaiLianRerank] 请求失败: status={}, body={}", response.code(), body);
                // fallback: 直接截取前 topN
                return candidates.subList(0, Math.min(topN, candidates.size()));
            }

            String respBody = response.body() != null ? response.body().string() : "";
            JsonObject respJson = gson.fromJson(respBody, JsonObject.class);

            return parseResponse(respJson, candidates, topN);
        } catch (IOException e) {
            log.error("[BaiLianRerank] 请求异常", e);
            return candidates.subList(0, Math.min(topN, candidates.size()));
        }
    }

    private List<RetrievedChunk> parseResponse(JsonObject respJson, List<RetrievedChunk> candidates, int topN) {
        if (respJson == null || !respJson.has("output")) {
            log.warn("[BaiLianRerank] 响应缺少 output: {}", respJson);
            return candidates.subList(0, Math.min(topN, candidates.size()));
        }

        JsonObject output = respJson.getAsJsonObject("output");
        if (output == null || !output.has("results")) {
            log.warn("[BaiLianRerank] output 缺少 results");
            return candidates.subList(0, Math.min(topN, candidates.size()));
        }

        JsonArray results = output.getAsJsonArray("results");
        if (CollUtil.isEmpty(results)) {
            return List.of();
        }

        List<RetrievedChunk> reranked = new ArrayList<>();
        Set<Integer> addedIndices = new HashSet<>();
        List<Double> scores = new ArrayList<>();
        int dropped = 0;

        for (JsonElement elem : results) {
            if (!elem.isJsonObject()){
                continue;
            }
            JsonObject item = elem.getAsJsonObject();
            if (!item.has("index")) {
                continue;
            }

            int idx = item.get("index").getAsInt();
            if (idx < 0 || idx >= candidates.size() || !addedIndices.add(idx)){
                continue;
            }

            RetrievedChunk src = candidates.get(idx);
            float score = item.has("relevance_score") && !item.get("relevance_score").isJsonNull()
                    ? item.get("relevance_score").getAsFloat()
                    : (src.getScore() != null ? src.getScore().floatValue() : 0f);

            // 相关度下限过滤：只对 API 返回的真实 relevance_score 生效（宁可少给，不塞弱相关块）
            if (minRelevanceScore > 0 && item.has("relevance_score")
                    && !item.get("relevance_score").isJsonNull()
                    && score < minRelevanceScore) {
                dropped++;
                continue;
            }

            reranked.add(new RetrievedChunk(
                    src.getContent(),
                    (double) score,
                    src.getMetadata(),
                    src.getChannelType()));
            scores.add((double) score);

            if (reranked.size() >= topN) {
                break;
            }
        }

        if (dropped > 0) {
            log.info("[BaiLianRerank] 相关度 < {} 过滤掉 {} 条（保留 {} 条）", minRelevanceScore, dropped, reranked.size());
        }

        // 补足不足 topN 的部分（仅当未开启下限过滤：过滤后的结果即为最终结论，不拿未精排的块凑数）
        if (minRelevanceScore <= 0 && reranked.size() < topN) {
            for (int i = 0; i < candidates.size() && reranked.size() < topN; i++) {
                if (!addedIndices.contains(i)) {
                    reranked.add(candidates.get(i));
                }
            }
        }

        // 逐条 relevance_score 埋点：与 @TraceStep("rag.rerank.call") 的 span 用 step_id 关联（emit 自动取 MDC）
        StructuredLog.emit("rerank.scores",
                Map.of("model", model, "min_relevance_score", minRelevanceScore,
                        "kept", reranked.size(), "dropped", dropped, "scores", scores));
        return reranked;
    }

    private List<RetrievedChunk> dedupById(List<RetrievedChunk> chunks) {
        List<RetrievedChunk> result = new ArrayList<>(chunks.size());
        Set<String> seen = new HashSet<>();
        for (RetrievedChunk c : chunks) {
            // metadata 落库字段名为 doc_id（见 IndexerNode），此处需一致，否则去重键恒空、去重失效
            String id = c.getMetadata() != null
                    ? String.valueOf(c.getMetadata().getOrDefault("doc_id", ""))
                    : "";
            if (seen.add(id + ":" + c.getContent().hashCode())) {
                result.add(c);
            }
        }
        return result;
    }
}
