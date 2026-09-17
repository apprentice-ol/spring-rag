package com.jjx.customer.platform.cache;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * 缓存命中计数器（进程内，自启动累计；重启清零）：供 /cache/stats 看板判断哪层缓存
 * 在白干、哪层值得调参（命中率/TTL/准入阈值的针对性优化依据）。
 * <p>只记真实触达 Redis 的结果——断路器旁路/层关闭不算 miss（否则降级期会把命中率打穿失真）。
 * <p>2026-09-11 升级：AtomicLong → LongAdder（分段累加，虚拟线程并发下无 CAS 自旋）；
 * 每层附 24h 分钟环形桶（槽打包「分钟纪元&lt;&lt;32 | 计数」，过期槽自然归零重写），
 * {@link #windowSnapshot} 提供滑动窗口视图——自启动累计跑一周后基本失去诊断价值，
 * "最近 1h 命中率"才反映当前流量下的缓存效果。内存：6 层 × 2 类型 × 1440 桶 × 8B ≈ 138KB。
 */
@Component
public class CacheMetrics {

    /** 分钟桶数（滑动窗口上限 24h） */
    private static final int WINDOW_MINUTES = 1440;

    private static final long MINUTE_MS = 60_000L;

    private final Clock clock;
    private final ConcurrentHashMap<String, Counters> counters = new ConcurrentHashMap<>();

    @Autowired
    public CacheMetrics() {
        this(Clock.systemDefaultZone());
    }

    CacheMetrics(Clock clock) {
        this.clock = clock;
    }

    static final class Counters {
        final LongAdder hits = new LongAdder();
        final LongAdder misses = new LongAdder();
        final AtomicLongArray hitBuckets = new AtomicLongArray(WINDOW_MINUTES);
        final AtomicLongArray missBuckets = new AtomicLongArray(WINDOW_MINUTES);
    }

    public void hit(String layer) {
        bump(layer, true);
    }

    public void miss(String layer) {
        bump(layer, false);
    }

    /** layer → [hits, misses] 快照（自启动累计）。 */
    public Map<String, long[]> snapshot() {
        Map<String, long[]> out = new LinkedHashMap<>();
        counters.forEach((layer, c) -> out.put(layer, new long[]{c.hits.sum(), c.misses.sum()}));
        return out;
    }

    /** layer → [hits, misses] 最近 window 的滑动窗口快照（上限 24h，超出按 24h 截断）。 */
    public Map<String, long[]> windowSnapshot(Duration window) {
        long minutes = window == null ? 0 : window.toMinutes();
        long minMinute = clock.millis() / MINUTE_MS - Math.min(Math.max(minutes, 0), WINDOW_MINUTES);
        Map<String, long[]> out = new LinkedHashMap<>();
        counters.forEach((layer, c) -> out.put(layer,
                new long[]{sum(c.hitBuckets, minMinute), sum(c.missBuckets, minMinute)}));
        return out;
    }

    private void bump(String layer, boolean hit) {
        Counters c = counters.computeIfAbsent(layer, k -> new Counters());
        (hit ? c.hits : c.misses).increment();
        long minute = clock.millis() / MINUTE_MS;
        bumpBucket(hit ? c.hitBuckets : c.missBuckets, (int) (minute % WINDOW_MINUTES), minute);
    }

    /** 槽内 CAS 自增：槽纪元 != 当前分钟时视为陈旧槽，从 1 重新计数。 */
    private static void bumpBucket(AtomicLongArray buckets, int idx, long minute) {
        long prev = buckets.get(idx);
        while (true) {
            long count = ((prev >>> 32) == minute ? (prev & 0xFFFF_FFFFL) : 0) + 1;
            long next = (minute << 32) | count;
            if (buckets.compareAndSet(idx, prev, next)) {
                return;
            }
            prev = buckets.get(idx);
        }
    }

    /** 窗口求和：只累加分钟纪元仍在窗内的槽。 */
    private static long sum(AtomicLongArray buckets, long minMinute) {
        long total = 0;
        for (int i = 0; i < WINDOW_MINUTES; i++) {
            long packed = buckets.get(i);
            if ((packed >>> 32) >= minMinute) {
                total += packed & 0xFFFF_FFFFL;
            }
        }
        return total;
    }
}
