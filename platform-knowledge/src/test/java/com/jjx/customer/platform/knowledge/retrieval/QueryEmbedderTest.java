package com.jjx.customer.platform.knowledge.retrieval;

import com.jjx.customer.platform.knowledge.retrieval.CacheKeys;
import com.jjx.customer.platform.cache.CacheProperties;
import com.jjx.customer.platform.cache.FakeCacheStore;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 查询 embedding 公共组件单测（计数 fake 模型 + 内存缓存）：
 * 缓存命中不调模型 / 坏缓存当 miss / 层关闭直通不写缓存。
 */
class QueryEmbedderTest {

    /** 计数 fake：固定返回 [1,2,3]。 */
    static class CountingModel implements EmbeddingModel {
        int calls;

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            calls++;
            return new EmbeddingResponse(List.of(new Embedding(new float[]{1f, 2f, 3f}, 0)));
        }

        @Override
        public float[] embed(org.springframework.ai.document.Document document) {
            calls++;
            return new float[]{1f, 2f, 3f};
        }
    }

    private QueryEmbedder embedder(CountingModel model, FakeCacheStore store, CacheProperties props) {
        return new QueryEmbedder(model, store, props);
    }

    @Test
    void 缓存命中_二次不调模型() throws Exception {
        CountingModel model = new CountingModel();
        FakeCacheStore store = new FakeCacheStore();

        QueryEmbedder embedder = embedder(model, store, new CacheProperties());
        float[] first = embedder.embed("发票冲红");
        float[] second = embedder.embed("发票冲红");

        assertEquals(1, model.calls);
        assertArrayEquals(first, second, 1e-6f);
        assertEquals(1, store.data.size());
    }

    @Test
    void 不同query不命中() throws Exception {
        CountingModel model = new CountingModel();
        QueryEmbedder embedder = embedder(model, new FakeCacheStore(), new CacheProperties());

        embedder.embed("问题一");
        embedder.embed("问题二");

        assertEquals(2, model.calls);
    }

    @Test
    void 坏缓存当miss_重算并覆盖() throws Exception {
        CountingModel model = new CountingModel();
        FakeCacheStore store = new FakeCacheStore();
        store.data.put(CacheKeys.embeddingKey(new CacheProperties().getEmbeddingModelTag(), "发票冲红"),
                "!!!not-base64!!!");

        QueryEmbedder embedder = embedder(model, store, new CacheProperties());
        float[] v = embedder.embed("发票冲红");

        assertEquals(1, model.calls, "坏缓存应触发重算");
        assertArrayEquals(new float[]{1f, 2f, 3f}, v, 1e-6f);
    }

    @Test
    void 层关闭直通_不写缓存() throws Exception {
        CountingModel model = new CountingModel();
        FakeCacheStore store = new FakeCacheStore();
        CacheProperties props = new CacheProperties();
        props.getEmbedding().setEnabled(false);

        QueryEmbedder embedder = embedder(model, store, props);
        embedder.embed("发票冲红");
        embedder.embed("发票冲红");

        assertEquals(2, model.calls, "层关闭应每次直调模型");
        assertEquals(0, store.data.size());
    }
}
