package com.jjx.customer.platform.business.orchestration.intent;
import com.jjx.customer.platform.intent.IntentResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.customer.platform.cache.CacheProperties;
import com.jjx.customer.platform.cache.FakeCacheStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 意图分类缓存装饰器单测：高置信命中不重调 / 低置信不缓存 / 层关闭直通。
 */
class CachingIntentClassifierTest {

    /** 计数 fake delegate（DefaultIntentClassifier 非_final，覆写 classify）。 */
    static class CountingClassifier extends DefaultIntentClassifier {
        final IntentResult next;
        int calls;

        CountingClassifier(IntentResult next) {
            super(null, null, null, null);
            this.next = next;
        }

        @Override
        public IntentResult classify(String question) {
            calls++;
            return next;
        }
    }

    private CachingIntentClassifier classifier(CountingClassifier delegate, FakeCacheStore store,
                                               CacheProperties props) {
        return new CachingIntentClassifier(delegate, store, props, new ObjectMapper());
    }

    @Test
    void 高置信缓存_二次不调delegate() {
        CountingClassifier delegate = new CountingClassifier(IntentResult.builder()
                .domain("knowledge").confidence(0.92).build());
        FakeCacheStore store = new FakeCacheStore();
        CachingIntentClassifier c = classifier(delegate, store, new CacheProperties());

        IntentResult first = c.classify("增值税发票怎么冲红");
        IntentResult second = c.classify("增值税发票怎么冲红");

        assertEquals(1, delegate.calls);
        assertEquals("knowledge", second.getDomain());
        assertEquals(0.92, second.getConfidence());
        assertEquals(1, store.data.size());
    }

    @Test
    void 不同问题不命中() {
        CountingClassifier delegate = new CountingClassifier(IntentResult.builder()
                .domain("knowledge").confidence(0.92).build());
        FakeCacheStore store = new FakeCacheStore();
        CachingIntentClassifier c = classifier(delegate, store, new CacheProperties());

        c.classify("问题一");
        c.classify("问题二");

        assertEquals(2, delegate.calls);
    }

    @Test
    void 低置信不缓存() {
        // delegate 异常/解析失败的 fallback 是 0.5，不应被缓存
        CountingClassifier delegate = new CountingClassifier(IntentResult.builder()
                .domain("knowledge").confidence(0.5)
                .reason("分类异常，默认走知识库查询").build());
        FakeCacheStore store = new FakeCacheStore();
        CachingIntentClassifier c = classifier(delegate, store, new CacheProperties());

        c.classify("模糊问题");
        c.classify("模糊问题");

        assertEquals(2, delegate.calls);
        assertEquals(0, store.data.size());
    }

    @Test
    void 层关闭直通() {
        CountingClassifier delegate = new CountingClassifier(IntentResult.builder()
                .domain("knowledge").confidence(0.92).build());
        FakeCacheStore store = new FakeCacheStore();
        CacheProperties props = new CacheProperties();
        props.getIntent().setEnabled(false);
        CachingIntentClassifier c = classifier(delegate, store, props);

        c.classify("同一问题");
        c.classify("同一问题");

        assertEquals(2, delegate.calls);
        assertEquals(0, store.data.size());
    }
}
