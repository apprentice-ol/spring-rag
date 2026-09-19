package com.jjx.customer.platform.knowledge.rerank;

import cn.hutool.core.collection.CollUtil;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jjx.customer.platform.knowledge.retrieval.ChunkIdentity;
import com.jjx.customer.platform.knowledge.retrieval.RetrievedChunk;
import com.jjx.customer.platform.knowledge.retrieval.SearchChannelType;
import com.jjx.ai.llmobservability.observation.logging.TelemetryStructuredLog;
import com.jjx.ai.llmobservability.observation.TelemetryTemplate;
import java.io.IOException;
import java.time.Duration;
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
import com.jjx.ai.llmobservability.observation.annotation.TelemetryStep;
import jakarta.annotation.PostConstruct;

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
    private final TelemetryTemplate obsTemplate;

    @Value("${rag.rerank.bailian.base-url:https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank}")
    private String baseUrl;

    @Value("${rag.rerank.bailian.api-key:}")
    private String apiKey;

    @Value("${rag.rerank.bailian.model:qwen3-rerank}")
    private String model;

    /** Rerank 相关度下限：低于此分的块不进上下文（0=关闭）。仅对 API 返回的真实 relevance_score 生效，fallback 不参与 */
    @Value("${rag.rerank.min-relevance-score:0.0}")
    private double minRelevanceScore;

    /** Rerank 独立超时：后处理器在通道 orTimeout 覆盖之外串行执行，共享客户端 120s readTimeout 会拖垮整条回答 */
    @Value("${rag.rerank.timeout-ms:8000}")
    private long rerankTimeoutMs;

    /**
     * 分批精排的批大小（0 或负数 = 不分批，一次喂全部）。
     *
     * <p>12 是实测的瘦请求区间上界（≈12 条 × 440 字 ≈ 5k 字符，远低于分数衰减拐点）；
     * 候选池 ≤ 该值时退化为单次调用，行为与分批前一致。</p>
     */
    @Value("${rag.rerank.batch-size:12}")
    private int batchSize;

    /** 规范化后的最终请求 URL（baseUrl 启动期固定，无需每次调用重算） */
    private String rerankUrl;

    /** 派生自共享客户端（共享连接池），仅覆盖 rerank 短超时 */
    private OkHttpClient rerankClient;

    public BaiLianRerankClient(OkHttpClient httpClient, Gson gson, TelemetryTemplate obsTemplate) {
        this.httpClient = httpClient;
        this.gson = gson;
        this.obsTemplate = obsTemplate;
    }

    @PostConstruct
    void initRerankEndpoint() {
        // 官方端点要求双段 .../services/rerank/text-rerank/text-rerank（2026 新版）。
        // 兼容配置里已含单段/双段的情况：缺哪段补哪段，避免配置笔误让 rerank 静默失效（400 退化回向量序）。
        String base = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        if (base.endsWith("/text-rerank/text-rerank")) {
            this.rerankUrl = base;
        } else if (base.endsWith("/text-rerank")) {
            this.rerankUrl = base + "/text-rerank";
        } else {
            this.rerankUrl = base + "/text-rerank/text-rerank";
        }
        this.rerankClient = httpClient.newBuilder()
                .callTimeout(Duration.ofMillis(rerankTimeoutMs))
                .build();
    }

    @Override
    public String provider() {
        return "bailian";
    }

    @Override
    @TelemetryStep("rag.rerank.call")
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN) {
        if (CollUtil.isEmpty(candidates) || topN <= 0) {
            return List.of();
        }

        // 标注模型名（通用动词，OTel GenAI key 由 telemetry 收口）
        obsTemplate.model(model);

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

    /**
     * 分批精排：候选超过 {@code batchSize} 时按批调用（每批都是瘦请求），跨批按分数合并后取 topN。
     *
     * <p><b>为什么必须分批</b>：实测 relevance_score 受请求总长影响——同一文档在 ~12k 字符的
     * 请求里得 0.349，在 ~55k 字符里掉到 0.208。候选池放大后（小块检索配套的 recallBudget /
     * candidateLimit 放宽）一次性喂进去会把全池分数压低、正确结果被 min-relevance 误杀。
     * 分批让每批评分都落在可比区间，合并后排序才有意义。</p>
     *
     * <p>实测（1954 燃烧三要素，109 候选）：一次喂 47k 字符 → 期望文档 0.2822（被砍）、全池仅 6 条过阈值；
     * 分 12 条一批 → 期望文档 0.3857（过阈值）、全池 60 条过阈值。</p>
     */
    private List<RetrievedChunk> doRerank(String query, List<RetrievedChunk> candidates, int topN) {
        if (batchSize <= 0 || candidates.size() <= batchSize) {
            return doRerankBatch(query, candidates, topN);
        }
        List<RetrievedChunk> merged = new ArrayList<>(candidates.size());
        for (int from = 0; from < candidates.size(); from += batchSize) {
            List<RetrievedChunk> batch = candidates.subList(from,
                    Math.min(from + batchSize, candidates.size()));
            merged.addAll(doRerankBatch(query, batch, batch.size()));
        }
        merged.sort((a, b) -> Double.compare(
                b.getScore() == null ? 0.0 : b.getScore(),
                a.getScore() == null ? 0.0 : a.getScore()));
        return merged.size() <= topN ? merged : new ArrayList<>(merged.subList(0, topN));
    }

    /** 单批精排（原 doRerank 逻辑，一批一次 HTTP）。 */
    private List<RetrievedChunk> doRerankBatch(String query, List<RetrievedChunk> candidates, int topN) {
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

        Request request = new Request.Builder()
                .url(rerankUrl)
                .post(RequestBody.create(gson.toJson(reqBody), JSON))
                .addHeader("Authorization", "Bearer " + apiKey)
                .build();

        try (Response response = rerankClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "";
                log.warn("[BaiLianRerank] 请求失败: status={}, body={}", response.code(), body);
                // fallback: 直接截取前 topN（拷贝而非 subList 视图，避免上游改动联动）
                return fallback(candidates, topN);
            }

            String respBody = response.body() != null ? response.body().string() : "";
            JsonObject respJson = gson.fromJson(respBody, JsonObject.class);

            return parseResponse(respJson, candidates, topN);
        } catch (IOException e) {
            log.error("[BaiLianRerank] 请求异常", e);
            return fallback(candidates, topN);
        }
    }

    /** 降级截断：返回前 topN 的<b>拷贝</b>（subList 视图与原列表联动，语义不一致） */
    private static List<RetrievedChunk> fallback(List<RetrievedChunk> candidates, int topN) {
        return List.copyOf(candidates.subList(0, Math.min(topN, candidates.size())));
    }

    private List<RetrievedChunk> parseResponse(JsonObject respJson, List<RetrievedChunk> candidates, int topN) {
        if (respJson == null || !respJson.has("output")) {
            log.warn("[BaiLianRerank] 响应缺少 output: {}", respJson);
            return fallback(candidates, topN);
        }

        JsonObject output = respJson.getAsJsonObject("output");
        if (output == null || !output.has("results")) {
            log.warn("[BaiLianRerank] output 缺少 results");
            return fallback(candidates, topN);
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

        // 逐条 relevance_score 埋点：与 @TelemetryStep("rag.rerank.call") 的 span 用 step_id 关联（emit 自动取 MDC）
        TelemetryStructuredLog.emit("rerank.scores",
                Map.of("model", model, "min_relevance_score", minRelevanceScore,
                        "kept", reranked.size(), "dropped", dropped, "scores", scores));
        return reranked;
    }

    private List<RetrievedChunk> dedupById(List<RetrievedChunk> chunks) {
        List<RetrievedChunk> result = new ArrayList<>(chunks.size());
        Set<String> seen = new HashSet<>();
        for (RetrievedChunk c : chunks) {
            // 去重键统一走 ChunkIdentity（doc_id + 全文 SHA-256）：与 DeduplicationPostProcessor /
            // FusionPostProcessor 同一套定义——三处各写一份正是先前口径分岔的来源。
            // 不用 hashCode：有碰撞概率（误去重）；content 为 null 时 ChunkIdentity 内部兜底不 NPE。
            if (seen.add(ChunkIdentity.of(c))) {
                result.add(c);
            }
        }
        return result;
    }
}
