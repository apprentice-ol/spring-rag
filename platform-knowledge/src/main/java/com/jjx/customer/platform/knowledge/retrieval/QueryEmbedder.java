package com.jjx.customer.platform.knowledge.retrieval;

import com.jjx.customer.platform.knowledge.retrieval.CacheKeys;
import com.jjx.customer.platform.cache.CacheProperties;
import com.jjx.customer.platform.cache.CacheStore;
import com.jjx.customer.platform.common.util.LlmCallGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * 查询 embedding 公共组件（从 VectorSearchChannel.embedCached 平移）：embedding 调用 +
 * Redis 缓存 + LlmCallGuard 超时的统一收口，向量检索通道与语义答案缓存共享同一份缓存。
 * <p>value = float[] 的 Base64（ByteBuffer 默认序往返一致保证）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryEmbedder {

    private static final Duration EMBED_TIMEOUT = Duration.ofSeconds(15);
    private static final String LAYER = "embedding";

    private final EmbeddingModel embeddingModel;
    private final CacheStore cacheStore;
    private final CacheProperties cacheProperties;

    /**
     * 查询向量化（带 Redis 缓存）。
     *
     * @throws Exception embedding 调用失败/超时（由调用方决定降级策略：向量通道返回空、语义缓存当 miss）
     */
    public float[] embed(String query) throws Exception {
        CacheProperties.Layer layer = cacheProperties.getEmbedding();
        if (cacheProperties.isEnabled() && layer.isEnabled()) {
            String key = CacheKeys.embeddingKey(cacheProperties.getEmbeddingModelTag(), query);
            Optional<String> cached = cacheStore.get(key, LAYER);
            if (cached.isPresent()) {
                try {
                    return decode(cached.get());
                } catch (Exception ignore) {
                    // 坏缓存当 miss
                }
            }
            float[] v = callModel(query);
            try {
                cacheStore.put(key, encode(v), layer.getTtl(), LAYER);
            } catch (Exception ignore) {
                // 写失败静默
            }
            return v;
        }
        return callModel(query);
    }

    private float[] callModel(String query) throws Exception {
        // bounded：embedding HTTP hang 不能无限占用调用线程（虚拟线程下也回收不了）
        return LlmCallGuard.call(() -> embeddingModel.embed(query), EMBED_TIMEOUT, "query embedding");
    }

    private static float[] decode(String base64) {
        byte[] bytes = Base64.getDecoder().decode(base64);
        float[] v = new float[bytes.length / Float.BYTES];
        ByteBuffer.wrap(bytes).asFloatBuffer().get(v);
        return v;
    }

    private static String encode(float[] v) {
        ByteBuffer bb = ByteBuffer.allocate(v.length * Float.BYTES);
        for (float f : v) {
            bb.putFloat(f);
        }
        return Base64.getEncoder().encodeToString(bb.array());
    }
}
