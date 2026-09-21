package com.jjx.customer.platform.business.knowledge.intent;
import com.jjx.customer.platform.intent.IntentResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.customer.platform.knowledge.retrieval.CacheKeys;
import com.jjx.customer.platform.cache.CacheProperties;
import com.jjx.customer.platform.cache.CacheStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 意图分类缓存装饰器（@Primary）：同问题直接回放分类结果，省一次 LLM 往返。
 * <p>只缓存 confidence >= 0.7 的成功结果——delegate 的异常/解析失败 fallback 是 0.5，
 * 天然不进缓存（避免把降级结果固化）。
 * <p>delegate 上的 {@code @TelemetryStep} 跨 bean 调用仍生效（AOP 切代理对象）；
 * 缓存命中路径无 LLM 调用、无 span，符合预期。
 */
@Slf4j
@Primary
@Component
public class CachingIntentClassifier implements IntentClassifier {

    private static final String LAYER = "intent";
    private static final double CACHE_MIN_CONFIDENCE = 0.7;

    private final DefaultIntentClassifier delegate;
    private final CacheStore cacheStore;
    private final CacheProperties cacheProperties;
    private final ObjectMapper objectMapper;

    public CachingIntentClassifier(DefaultIntentClassifier delegate,
                                   CacheStore cacheStore,
                                   CacheProperties cacheProperties,
                                   ObjectMapper objectMapper) {
        this.delegate = delegate;
        this.cacheStore = cacheStore;
        this.cacheProperties = cacheProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public IntentResult classify(String question) {
        return classify(question, "");
    }

    /**
     * 带历史分类：历史非空时旁路缓存，也不写缓存。
     *
     * <p><b>为什么旁路</b>：缓存 key 只有 question，而裸关键词的意图恰恰由历史决定——
     * "Refresh-Token" 在 token 概念话题里是 knowledge，在新会话里才可能是排障。
     * 命中/写入都会把某个历史语境的结论串给另一个语境。首轮（无历史）照常走缓存。</p>
     */
    @Override
    public IntentResult classify(String question, String recentHistory) {
        if (recentHistory != null && !recentHistory.isBlank()) {
            return delegate.classify(question, recentHistory);
        }
        CacheProperties.Layer layer = cacheProperties.getIntent();
        if (!cacheProperties.isEnabled() || !layer.isEnabled()) {
            return delegate.classify(question);
        }
        String key = CacheKeys.intentKey(question);
        String cached = cacheStore.get(key, LAYER).orElse(null);
        if (cached != null) {
            try {
                IntentResult result = objectMapper.readValue(cached, IntentResult.class);
                if (result != null) {
                    return result;
                }
            } catch (Exception e) {
                log.warn("[意图缓存] 坏缓存当 miss: {}", e.getMessage());
            }
        }
        IntentResult result = delegate.classify(question);
        if (result != null && result.getConfidence() >= CACHE_MIN_CONFIDENCE) {
            try {
                cacheStore.put(key, objectMapper.writeValueAsString(result), layer.getTtl(), LAYER);
            } catch (Exception e) {
                log.warn("[意图缓存] 写入失败（忽略）: {}", e.getMessage());
            }
        }
        return result;
    }
}
