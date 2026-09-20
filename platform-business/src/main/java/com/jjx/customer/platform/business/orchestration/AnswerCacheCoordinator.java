package com.jjx.customer.platform.business.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.ai.llmobservability.observation.logging.TelemetryLogger;
import com.jjx.customer.platform.business.engine.PromptFingerprintResolver;
import com.jjx.customer.platform.cache.CacheFrequencyPolicy;
import com.jjx.customer.platform.cache.CacheProperties;
import com.jjx.customer.platform.cache.CacheStore;
import com.jjx.customer.platform.cache.FrequencyTracker;
import com.jjx.customer.platform.delivery.DeliveryPortFactory;
import com.jjx.customer.platform.knowledge.retrieval.CacheKeys;
import com.jjx.customer.platform.knowledge.retrieval.DocumentVersionStamp;
import com.jjx.customer.platform.knowledge.retrieval.SearchContext;
import com.jjx.customer.platform.knowledge.retrieval.SemanticAnswerCache;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * 两级答案缓存协调器（自 {@link ChatOrchestrator} 决策链 6.5 步拆出）：
 * exact → 语义两级查询与重放、频率准入、热度 TTL 延长、流式收尾的缓存写回。
 * 同问题或同义问题直接重放，省检索+生成整链。
 */
@Component
@RequiredArgsConstructor
public class AnswerCacheCoordinator<S> {

    private static final TelemetryLogger log = TelemetryLogger.of(AnswerCacheCoordinator.class);

    private final CacheStore cacheStore;
    private final CacheProperties cacheProperties;
    private final FrequencyTracker frequencyTracker;
    private final CacheFrequencyPolicy frequencyPolicy;
    private final SemanticAnswerCache semanticAnswerCache;
    private final ObjectMapper objectMapper;
    private final DocumentVersionStamp docverStamp;
    private final PromptFingerprintResolver promptFingerprintResolver;
    private final DeliveryPortFactory<S> deliveryPortFactory;

    /** 答案缓存载荷（Jackson record 构造器绑定；messageId 用于 exact 命中时联动语义记录计数，旧载荷无此字段反序列化为 null）。 */
    record CachedAnswer(String answer, String citationsJson, String paradigm, Long messageId) {
    }

    /** 缓存 key；层未启用或 docver 不可用（Redis 挂）返回 null = 本轮不读不写。
     *  key 含 prompt 内容指纹（改任一层 prompt ⇒ 自动失效，F6 验收口径）。 */
    public String answerCacheKey(String question, SearchContext searchCtx, String paradigm) {
        if (!cacheProperties.isEnabled() || !cacheProperties.getAnswer().isEnabled()) {
            return null;
        }
        String collectionKey = searchCtx.getCollectionId() == null
                ? DocumentVersionStamp.ALL_COLLECTIONS : String.valueOf(searchCtx.getCollectionId());
        long version = docverStamp.current(collectionKey);
        if (version < 0) {
            return null;
        }
        return CacheKeys.answerKey(question, paradigm, version,
                promptFingerprintResolver.promptHash(paradigm));
    }

    /** 频率观察（窗口内首次出现的问题不写 exact 缓存——长尾防污染——出现满阈值才写）。 */
    public long observe(String cacheKey) {
        return frequencyTracker.observe(cacheKey);
    }

    /** 频率准入：本轮是否允许写 exact 缓存。 */
    public boolean admits(long freq) {
        return frequencyPolicy.admits(freq, cacheProperties.getAnswer().getAdmissionThreshold(), false);
    }

    /**
     * exact 命中重放：解析载荷 + 重放 + 热度延长。
     *
     * @return true = 已重放并收尾，调用方直接 return
     */
    public boolean tryReplayCachedAnswer(String cacheKey, long freq, String conversationId, S sink,
                                         String otelTraceId, String paradigm) {
        String json = cacheStore.get(cacheKey, "answer").orElse(null);
        if (json == null) {
            return false;
        }
        CachedAnswer ca;
        try {
            ca = objectMapper.readValue(json, CachedAnswer.class);
        } catch (Exception e) {
            log.warn("[对话编排] 答案缓存坏载荷当 miss: {}", e.getMessage());
            return false;
        }
        if (ca == null || !StringUtils.hasText(ca.answer())) {
            return false;
        }
        replayAnswer(ca.answer(), ca.citationsJson(), conversationId, sink, otelTraceId, paradigm);
        // 热度延长：重放成功且窗口计数达档位时延长 TTL
        java.time.Duration scaled = frequencyPolicy.scaledTtl(freq, cacheProperties.getAnswer().getTtl());
        if (scaled != null && !scaled.equals(cacheProperties.getAnswer().getTtl())) {
            cacheStore.touch(cacheKey, scaled, "answer");
        }
        // 命中口径联动：exact 层命中也让对应语义记录的命中次数 +1（看板「命中」不因服务层不同而冻结）
        semanticAnswerCache.bumpByMessageId(ca.messageId());
        log.info("[对话编排] 答案缓存命中重放(exact): 会话ID={}, 范式={}", conversationId, paradigm);
        return true;
    }

    /**
     * 语义层：同义不同字面的问题（exact 的频率计数被变体分散，正是语义缓存的靶子）。
     * 语义命中回写 exact：本问下次免 embed 直达（exact 快、语义准，两层各取所长；
     * messageId 一并存入，exact 命中时可联动语义记录计数）。
     *
     * @return true = 语义命中已重放，调用方直接 return
     */
    public boolean replaySemanticIfHit(String ruleNormalized, String paradigm, String answerCacheKey,
                                       String conversationId, S sink, String otelTraceId) {
        if (cacheProperties.isEnabled() && cacheProperties.getSemantic().isEnabled()) {
            Optional<SemanticAnswerCache.SemanticHit> hit = semanticAnswerCache.lookup(
                    ruleNormalized, paradigm, promptFingerprintResolver.promptHash(paradigm));
            if (hit.isPresent() && replayAnswer(hit.get().answer(), hit.get().citationsJson(),
                    conversationId, sink, otelTraceId, paradigm)) {
                if (answerCacheKey != null) {
                    try {
                        cacheStore.put(answerCacheKey, objectMapper.writeValueAsString(new CachedAnswer(
                                        hit.get().answer(), hit.get().citationsJson(), paradigm,
                                        hit.get().messageId())),
                                cacheProperties.getAnswer().getTtl(), "answer");
                    } catch (Exception cacheEx) {
                        log.warn("[对话编排] 语义命中回写 exact 失败（忽略）: {}", cacheEx.getMessage());
                    }
                }
                return true;
            }
        }
        return false;
    }

    /**
     * 重放缓存答案（exact/语义共用，同步，镜像 streamDirectAnswer 模式——请求线程直接落库）：
     * citations → 分段 message → 落库（带 citations）→ meta → complete。
     * 无 trace 事件、无 agentTraceService.record（by design：没有 agent 执行就没有轨迹）。
     */
    private boolean replayAnswer(String answer, String citationsJson, String conversationId,
                                 S sink, String otelTraceId, String paradigm) {
        deliveryPortFactory.begin(sink, conversationId, null, otelTraceId, paradigm, null)
                .emitCachedReplay(conversationId, answer, citationsJson, paradigm);
        return true;
    }

    /**
     * 流式回答收尾的缓存写回（RagAnswerStreamer 完成回调调用）：
     * exact 写回（频率准入未过时 key 为 null，跳过）+ 语义层落库。
     * 仅 exact put 有 try/catch 兜底；语义层异常沿调用链走外层统一兜底（与拆分前一致）。
     *
     * @param cacheable 本轮答案是否可跨会话复用。false（= 本轮带了对话历史）时<b>两层都不写</b>：
     *                  答案缓存没有会话维度（key = 问题 + 范式 + docver + prompt 指纹），而带指代的
     *                  追问句（"它的配置呢"）在不同会话里字面与向量都相同——写进去就会在下个会话
     *                  被重放成"另一个话题的答案"。
     *                  <p>别拿 {@code answerCacheKey == null} 兼职表达这件事：exact 因频率准入未过
     *                  而为 null 时语义层<b>照写不误</b>是刻意设计（字面不同的重复问题正是语义缓存的靶子）。</p>
     */
    public void storeAnswer(String answerCacheKey, String answer, String citationsJson,
                            String paradigm, String normalizedQuestion, Long messageId, boolean cacheable) {
        if (!cacheable) {
            return;
        }
        if (answerCacheKey != null && StringUtils.hasText(answer)) {
            try {
                cacheStore.put(answerCacheKey,
                        objectMapper.writeValueAsString(new CachedAnswer(
                                answer, citationsJson, paradigm, messageId)),
                        cacheProperties.getAnswer().getTtl(), "answer");
            } catch (Exception cacheEx) {
                log.warn("[对话编排] 答案缓存写入失败（忽略）: {}", cacheEx.getMessage());
            }
        }
        semanticAnswerCache.store(normalizedQuestion, answer, citationsJson,
                paradigm, promptFingerprintResolver.promptHash(paradigm), messageId);
    }
}
