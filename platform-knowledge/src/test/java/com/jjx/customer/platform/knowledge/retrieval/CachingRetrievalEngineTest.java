package com.jjx.customer.platform.knowledge.retrieval;
import com.jjx.customer.platform.cache.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.customer.platform.knowledge.retrieval.MultiChannelRetrievalEngine;
import com.jjx.customer.platform.knowledge.retrieval.RetrievedChunk;
import com.jjx.customer.platform.knowledge.retrieval.SearchChannel;
import com.jjx.customer.platform.knowledge.retrieval.SearchChannelResult;
import com.jjx.customer.platform.knowledge.retrieval.SearchChannelType;
import com.jjx.customer.platform.knowledge.retrieval.SearchContext;
import com.jjx.customer.platform.config.properties.ChatProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索结果缓存装饰器单测（fake 通道 + 内存 CacheStore + 可控 docver + 固定窗口计数）：
 * 命中免检索 / Jackson 往返保真 / docver 失效 / 空结果不缓存 / 坏 JSON 当 miss / 开关直通
 * / 频率准入（首次不写、满阈值写、eval 旁路）/ 热度命中延长 TTL。
 */
class CachingRetrievalEngineTest {

    /** 计数 fake 通道：每次返回带完整 metadata 的 chunk（供往返断言）。 */
    static class CountingChannel implements SearchChannel {
        int calls;
        boolean empty;

        @Override
        public String getName() {
            return "vector";
        }

        @Override
        public SearchChannelType getType() {
            return SearchChannelType.VECTOR;
        }

        @Override
        public boolean isEnabled(SearchContext context) {
            return true;
        }

        @Override
        public SearchChannelResult search(SearchContext context) {
            calls++;
            if (empty) {
                return SearchChannelResult.builder().channelType(getType())
                        .channelName(getName()).chunks(List.of()).latencyMs(1).build();
            }
            RetrievedChunk c = new RetrievedChunk("冲红流程第一步", 0.87,
                    Map.of("doc_id", "d-001", "doc_name", "发票手册.md", "chunk_index", 3),
                    SearchChannelType.VECTOR);
            c.setOriginalScore(0.91);
            return SearchChannelResult.builder().channelType(getType())
                    .channelName(getName()).chunks(List.of(c)).latencyMs(1).build();
        }
    }

    /** 固定窗口计数的 fake 频率统计器（覆写 observe，绕过进程内窗口）。 */
    static class FixedTracker extends FrequencyTracker {
        final long count;

        FixedTracker(long count) {
            super(null);
            this.count = count;
        }

        @Override
        public long observe(String cacheKey) {
            return count;
        }
    }

    /** 固定版本号的 DocumentVersionStamp（绕过 Redis）。 */
    private static DocumentVersionStamp stamp(long ver) {
        return new DocumentVersionStamp(null, null) {
            @Override
            public long current(String collectionKey) {
                return ver;
            }

            @Override
            public void bump(Long collectionId) {
            }
        };
    }

    private SearchContext ctx() {
        return SearchContext.builder().query("发票冲红").topK(10).threshold(0.0).build();
    }

    private CachingRetrievalEngine engine(CountingChannel channel, FakeCacheStore store,
                                          CacheProperties props, long ver, long freq) {
        MultiChannelRetrievalEngine delegate = new MultiChannelRetrievalEngine(
                List.of(channel), List.of(), new ChatProperties(), Runnable::run);
        return new CachingRetrievalEngine(delegate, store, props, new ObjectMapper(), stamp(ver),
                new FixedTracker(freq), new CacheFrequencyPolicy(props));
    }

    @Test
    void 命中后不再检索_往返字段保真() {
        CountingChannel channel = new CountingChannel();
        FakeCacheStore store = new FakeCacheStore();
        CachingRetrievalEngine cacheEngine = engine(channel, store, new CacheProperties(), 1, 3);

        MultiChannelRetrievalEngine.RetrievalResult first = cacheEngine.retrieve(ctx());
        MultiChannelRetrievalEngine.RetrievalResult second = cacheEngine.retrieve(ctx());

        assertEquals(1, channel.calls);
        assertFalse(second.isEmpty());
        // 第二次来自缓存（Jackson 往返），关键字段保真
        RetrievedChunk chunk = second.getFinalChunks().get(0);
        assertEquals("冲红流程第一步", chunk.getContent());
        assertEquals("d-001", chunk.getMetadata().get("doc_id"));
        assertEquals("发票手册.md", chunk.getMetadata().get("doc_name"));
        assertEquals("3", String.valueOf(chunk.getMetadata().get("chunk_index")));
        assertEquals(SearchChannelType.VECTOR, chunk.getChannelType());
        assertEquals(first.getFinalChunks().get(0).getScore(), chunk.getScore());
        assertEquals(1, (int) second.getFinalChunks().get(0).getRank());
        // 窗口计数 3 未达热度档（5）：命中不 touch
        assertEquals(0, store.touched.size());
    }

    @Test
    void 频率准入_首次不写满阈值才写() {
        CountingChannel channel = new CountingChannel();
        FakeCacheStore store = new FakeCacheStore();

        // 窗口内第 1 次出现：不写（默认阈值 2）
        engine(channel, store, new CacheProperties(), 1, 1).retrieve(ctx());
        assertEquals(1, channel.calls);
        assertEquals(0, store.data.size());

        // 第 2 次出现：准入的代价 = 再检索一遍，结果写入
        CachingRetrievalEngine second = engine(channel, store, new CacheProperties(), 1, 2);
        second.retrieve(ctx());
        assertEquals(2, channel.calls);
        assertEquals(1, store.data.size());

        // 第 3 次起：命中
        second.retrieve(ctx());
        assertEquals(2, channel.calls);
    }

    @Test
    void eval跑批旁路准入_首次即写() {
        CountingChannel channel = new CountingChannel();
        FakeCacheStore store = new FakeCacheStore();
        SearchContext evalCtx = SearchContext.builder().query("发票冲红").topK(10)
                .metadata(Map.of("intent", "EVAL")).build();

        engine(channel, store, new CacheProperties(), 1, 1).retrieve(evalCtx);

        assertEquals(1, store.data.size(), "eval 参数扫描每个组合都是新 key，必须旁路准入否则提速归零");
    }

    @Test
    void 热度命中延长TTL_一档() {
        CountingChannel channel = new CountingChannel();
        FakeCacheStore store = new FakeCacheStore();
        // 窗口计数 10：一档（>=5），TTL 10m ×4 = 40m
        CachingRetrievalEngine cacheEngine = engine(channel, store, new CacheProperties(), 1, 10);

        cacheEngine.retrieve(ctx());  // 写入
        cacheEngine.retrieve(ctx());  // 命中 → touch

        assertEquals(1, channel.calls);
        assertEquals(1, store.touched.size());
        assertEquals(Duration.ofMinutes(40), store.ttls.values().iterator().next());
    }

    @Test
    void 热度命中延长TTL_二档顶格() {
        CountingChannel channel = new CountingChannel();
        FakeCacheStore store = new FakeCacheStore();
        CachingRetrievalEngine cacheEngine = engine(channel, store, new CacheProperties(), 1, 25);

        cacheEngine.retrieve(ctx());
        cacheEngine.retrieve(ctx());

        assertEquals(Duration.ofMinutes(100), store.ttls.values().iterator().next());
    }

    @Test
    void docver变则重新检索() {
        CountingChannel channel = new CountingChannel();
        FakeCacheStore store = new FakeCacheStore();
        CachingRetrievalEngine v1 = engine(channel, store, new CacheProperties(), 1, 3);
        CachingRetrievalEngine v2 = engine(channel, store, new CacheProperties(), 2, 3);
        v1.retrieve(ctx());
        v2.retrieve(ctx());
        assertEquals(2, channel.calls);
    }

    @Test
    void 空结果不缓存() {
        CountingChannel channel = new CountingChannel();
        channel.empty = true;
        FakeCacheStore store = new FakeCacheStore();
        CachingRetrievalEngine cacheEngine = engine(channel, store, new CacheProperties(), 1, 3);
        cacheEngine.retrieve(ctx());
        cacheEngine.retrieve(ctx());
        assertEquals(2, channel.calls);
        assertEquals(0, store.data.size());
    }

    @Test
    void 坏JSON当miss不抛() {
        CountingChannel channel = new CountingChannel();
        FakeCacheStore store = new FakeCacheStore();
        CachingRetrievalEngine cacheEngine = engine(channel, store, new CacheProperties(), 1, 3);
        // 预填坏载荷
        store.data.put(CacheKeys.retrievalKey(ctx(), 1), "{not-json");
        MultiChannelRetrievalEngine.RetrievalResult rr = cacheEngine.retrieve(ctx());
        assertFalse(rr.isEmpty());
        assertEquals(1, channel.calls);
    }

    @Test
    void 层关闭直通() {
        CountingChannel channel = new CountingChannel();
        FakeCacheStore store = new FakeCacheStore();
        CacheProperties props = new CacheProperties();
        props.getRetrieval().setEnabled(false);
        CachingRetrievalEngine cacheEngine = engine(channel, store, props, 1, 3);
        cacheEngine.retrieve(ctx());
        cacheEngine.retrieve(ctx());
        assertEquals(2, channel.calls);
        assertEquals(0, store.data.size());
    }

    @Test
    void 不同参数不互串() {
        CountingChannel channel = new CountingChannel();
        FakeCacheStore store = new FakeCacheStore();
        CachingRetrievalEngine cacheEngine = engine(channel, store, new CacheProperties(), 1, 3);
        SearchContext a = ctx();
        SearchContext b = ctx();
        b.setTopK(8);
        cacheEngine.retrieve(a);
        cacheEngine.retrieve(b);
        assertEquals(2, channel.calls);
    }

    @Test
    void 频率功能关闭时准入恒过_等价旧行为() {
        CountingChannel channel = new CountingChannel();
        FakeCacheStore store = new FakeCacheStore();
        // tracker 返回 UNAVAILABLE（功能关闭/Redis 故障语义）→ fail-open，首次即写
        CachingRetrievalEngine cacheEngine = engine(channel, store, new CacheProperties(), 1,
                FrequencyTracker.UNAVAILABLE);
        cacheEngine.retrieve(ctx());
        assertEquals(1, store.data.size());
        assertTrue(store.ttls.containsValue(Duration.ofMinutes(10)));
    }
}
