package com.jjx.customer.platform.knowledge.retrieval;
import com.jjx.customer.platform.cache.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.customer.platform.knowledge.retrieval.MultiChannelRetrievalEngine;
import com.jjx.customer.platform.knowledge.retrieval.RetrievalEngine;
import com.jjx.customer.platform.knowledge.retrieval.SearchContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 检索结果缓存装饰器（@Primary，收益最大的缓存层）：命中省掉 embedding 调用 + pgvector 查询 +
 * 百炼 rerank + RRF 整条链。
 * <p>失效 = docver 版本号（文档重灌 INCR）+ TTL 兜底；key 含 SearchContext 全参数（见
 * {@link CacheKeys#retrievalKey} 的硬约束——eval 参数扫描的正确性依赖于此）。
 * <p>频率策略（Sentinel-lite）：窗口内出现满 {@code admission-threshold}（默认 2）才写——
 * 长尾一次性问题不进缓存；命中且窗口计数达热度档位时延长 TTL（只延长不缩短；key 含 docver，
 * TTL 只是内存旋钮，延长零正确性风险）。eval 跑批（metadata.intent=EVAL）旁路准入。
 * <p>降级三连：Redis 不可用（docver=-1）整体旁路；坏 JSON 当 miss；空结果不缓存
 * （可能是通道超时瞬态，缓存会放大故障）。
 */
@Slf4j
@Primary
@Component
public class CachingRetrievalEngine implements RetrievalEngine {

    private static final String LAYER = "retrieval";

    private final MultiChannelRetrievalEngine delegate;
    private final CacheStore cacheStore;
    private final CacheProperties cacheProperties;
    private final ObjectMapper objectMapper;
    private final DocumentVersionStamp docver;
    private final FrequencyTracker frequencyTracker;
    private final CacheFrequencyPolicy frequencyPolicy;

    public CachingRetrievalEngine(MultiChannelRetrievalEngine delegate,
                                  CacheStore cacheStore,
                                  CacheProperties cacheProperties,
                                  ObjectMapper objectMapper,
                                  DocumentVersionStamp docver,
                                  FrequencyTracker frequencyTracker,
                                  CacheFrequencyPolicy frequencyPolicy) {
        this.delegate = delegate;
        this.cacheStore = cacheStore;
        this.cacheProperties = cacheProperties;
        this.objectMapper = objectMapper;
        this.docver = docver;
        this.frequencyTracker = frequencyTracker;
        this.frequencyPolicy = frequencyPolicy;
    }

    @Override
    public MultiChannelRetrievalEngine.RetrievalResult retrieve(SearchContext context) {
        CacheProperties.Layer layer = cacheProperties.getRetrieval();
        if (!cacheProperties.isEnabled() || !layer.isEnabled()) {
            return delegate.retrieve(context);
        }
        String collectionKey = context.getCollectionId() == null
                ? DocumentVersionStamp.ALL_COLLECTIONS : String.valueOf(context.getCollectionId());
        long version = docver.current(collectionKey);
        if (version < 0) {
            // Redis 不可用：不读不写，整体旁路
            return delegate.retrieve(context);
        }
        String key = CacheKeys.retrievalKey(context, version);
        // 频率观测放在 get 前（命中/未命中都计数，语义=查询出现频率）
        long freq = frequencyTracker.observe(key);
        String cached = cacheStore.get(key, LAYER).orElse(null);
        if (cached != null) {
            try {
                MultiChannelRetrievalEngine.RetrievalResult rr =
                        objectMapper.readValue(cached, MultiChannelRetrievalEngine.RetrievalResult.class);
                if (rr != null && rr.getFinalChunks() != null) {
                    // 热度延长：窗口计数达档位才 touch（未达档 scaledTtl 返回原值，不发起 EXPIRE）
                    Duration scaled = frequencyPolicy.scaledTtl(freq, layer.getTtl());
                    if (scaled != null && !scaled.equals(layer.getTtl())) {
                        cacheStore.touch(key, scaled, LAYER);
                    }
                    log.info("[检索缓存] 命中: {} 条（{}）", rr.getFinalChunks().size(), shortKey(key));
                    return rr;
                }
            } catch (Exception e) {
                log.warn("[检索缓存] 坏缓存当 miss（{}）: {}", shortKey(key), e.getMessage());
            }
        }
        MultiChannelRetrievalEngine.RetrievalResult result = delegate.retrieve(context);
        if (!result.isEmpty()
                && frequencyPolicy.admits(freq, layer.getAdmissionThreshold(), isEval(context))) {
            try {
                cacheStore.put(key, objectMapper.writeValueAsString(result), layer.getTtl(), LAYER);
            } catch (Exception e) {
                log.warn("[检索缓存] 写入失败（忽略）: {}", e.getMessage());
            }
        }
        return result;
    }

    /** eval 跑批标记（EvalRunner 固定 metadata.intent=EVAL）：参数扫描下每个组合都是新 key，须旁路准入。 */
    private static boolean isEval(SearchContext context) {
        Object v = context.getMetadata() == null ? null : context.getMetadata().get("intent");
        return "EVAL".equals(v);
    }

    private static String shortKey(String key) {
        return key.length() <= 40 ? key : "…" + key.substring(key.length() - 12);
    }
}
