package com.jjx.customer.platform.cache;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * 频率统计器（进程内滑动窗口计数，Sentinel LeapArray 的朴素版）：对每个缓存 key 维护
 * 「最近 {@code rag.cache.freq-window} 内出现次数」——16 个环形时间桶，每槽打包
 * 「桶纪元&lt;&lt;32 | 计数」的 long（槽纪元过期则自然归零重写，无需清理线程），写 O(1) CAS，
 * 窗口求和 O(16)。命中与未命中都会调用 {@link #observe}（计数语义是"查询出现频率"，
 * 不是"未命中频率"）。只做统计，不做决策——准入/热度延长由 {@link CacheFrequencyPolicy}
 * 承担（统计与决策都是纯内存算术，各自可单测）。
 *
 * <p>2026-09-11 由 Redis INCR+EXPIRE 改进程内：原实现每次 observe 两次 Redis 往返
 * （retrieval + answer 各一次 = 每请求固定多 4 次 RTT），缓存全命中时统计开销反超缓存收益，
 * 且 Redis 故障时频率策略跟着失效。进程内化后热路径零 Redis 调用，降级期准入照常工作；
 * 旧 {@code rag:cache:freq:*} key 无人续期，TTL 到期自然消失，无需迁移。
 *
 * <p>内存有界：key 数超 {@code rag.cache.freq-max-keys}（默认 1 万）时先清理窗口和为 0 的
 * 冷 key（60s 节流），仍满则新 key 按首次出现处理（不注册、fail-open，不阻塞查询）。
 *
 * <p>进程内语义的已知取舍：多实例部署下各实例只看到部分流量（计数偏低，当前单实例部署
 * 不构成问题）；重启清零（15 分钟量级的短期统计，损失可接受）。类/方法非 final，供单测 Scripted。
 */
@Component
public class FrequencyTracker {

    /** 统计不可用（功能关闭）的哨兵：决策层按不限制处理 */
    public static final long UNAVAILABLE = -1L;

    /** 环形桶数（2 的幂：位与取模；15m 窗口 → 每桶约 56s） */
    private static final int BUCKETS = 16;

    /** 冷 key 清理的最小间隔（满容量时高并发下不反复全表遍历） */
    private static final long SWEEP_INTERVAL_MS = 60_000L;

    private final CacheProperties cacheProperties;
    private final Clock clock;

    private final ConcurrentHashMap<String, AtomicLongArray> windows = new ConcurrentHashMap<>();
    private final AtomicLong lastSweepAt = new AtomicLong();

    @Autowired
    public FrequencyTracker(CacheProperties cacheProperties) {
        this(cacheProperties, Clock.systemDefaultZone());
    }

    FrequencyTracker(CacheProperties cacheProperties, Clock clock) {
        this.cacheProperties = cacheProperties;
        this.clock = clock;
    }

    /** 频率功能是否启用（总开关 + freqWindow 为正）。 */
    public boolean enabled() {
        return cacheProperties != null && cacheProperties.isEnabled()
                && cacheProperties.getFreqWindow() != null
                && !cacheProperties.getFreqWindow().isNegative()
                && !cacheProperties.getFreqWindow().isZero();
    }

    /**
     * 观测一次缓存查询（命中/未命中都算），返回滑动窗口内累计次数（含本次）。
     *
     * @return 窗口计数；功能关闭返回 {@link #UNAVAILABLE}
     */
    public long observe(String cacheKey) {
        if (!enabled()) {
            return UNAVAILABLE;
        }
        long windowMs = cacheProperties.getFreqWindow().toMillis();
        long bucketMs = Math.max(1, windowMs / BUCKETS);
        long now = clock.millis();

        AtomicLongArray window = windows.get(cacheKey);
        if (window == null) {
            register(cacheKey, now);
            window = windows.get(cacheKey);
            if (window == null) {
                // 超 maxKeys 且清理无果：不注册，按首次出现处理（长尾 key 本就是准入要拦的对象）
                return 1L;
            }
        }
        bumpBucket(window, bucketIndexOf(now, bucketMs), now / bucketMs);
        return sum(window, minEpoch(now, windowMs, bucketMs));
    }

    /** 注册新 key 的窗口（满容量先清理冷 key）。putIfAbsent 竞态输掉也无妨——重取即可。 */
    private void register(String cacheKey, long now) {
        if (windows.size() < maxKeys() || sweep(now)) {
            windows.putIfAbsent(cacheKey, new AtomicLongArray(BUCKETS));
        }
    }

    /**
     * 清理窗口和为 0 的冷 key（桶纪元全出窗 = 至少一个窗口期无访问），60s 节流。
     *
     * @return 清理后（或节流窗口内复查）是否仍有空位
     */
    private boolean sweep(long now) {
        long last = lastSweepAt.get();
        if (now - last < SWEEP_INTERVAL_MS || !lastSweepAt.compareAndSet(last, now)) {
            return windows.size() < maxKeys();
        }
        long windowMs = cacheProperties.getFreqWindow().toMillis();
        long bucketMs = Math.max(1, windowMs / BUCKETS);
        long min = minEpoch(now, windowMs, bucketMs);
        windows.forEach((k, w) -> {
            if (sum(w, min) == 0) {
                windows.remove(k); // 与并发 observe 竞态至多丢一次计数，统计近似可接受
            }
        });
        return windows.size() < maxKeys();
    }

    private int maxKeys() {
        return Math.max(1, cacheProperties.getFreqMaxKeys());
    }

    /** 槽内 CAS 自增：槽纪元 != 当前纪元时视为陈旧槽，从 1 重新计数。 */
    private static void bumpBucket(AtomicLongArray window, int idx, long epoch) {
        long prev = window.get(idx);
        while (true) {
            long count = (unpackEpoch(prev) == epoch ? unpackCount(prev) : 0) + 1;
            if (window.compareAndSet(idx, prev, pack(epoch, count))) {
                return;
            }
            prev = window.get(idx);
        }
    }

    /** 窗口求和：只累加桶纪元仍在窗内的槽（单槽读原子，整体尽力而为一致）。 */
    private static long sum(AtomicLongArray window, long minEpoch) {
        long total = 0;
        for (int i = 0; i < BUCKETS; i++) {
            long packed = window.get(i);
            if (unpackEpoch(packed) >= minEpoch) {
                total += unpackCount(packed);
            }
        }
        return total;
    }

    /** 窗口起点的桶纪元（floor）：桶 e 覆盖 [e·bucketMs, (e+1)·bucketMs)，与 (now-windowMs, now] 有交集即计入。 */
    private static long minEpoch(long now, long windowMs, long bucketMs) {
        return (now - windowMs) / bucketMs;
    }

    private static int bucketIndexOf(long now, long bucketMs) {
        return (int) ((now / bucketMs) & (BUCKETS - 1));
    }

    private static long pack(long epoch, long count) {
        return (epoch << 32) | count;
    }

    private static long unpackEpoch(long packed) {
        return packed >>> 32;
    }

    private static long unpackCount(long packed) {
        return packed & 0xFFFF_FFFFL;
    }
}
