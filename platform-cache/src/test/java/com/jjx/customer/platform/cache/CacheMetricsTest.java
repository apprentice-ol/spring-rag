package com.jjx.customer.platform.cache;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 缓存命中计数器单测（可控时钟）：累计快照 / 滑动窗口快照不含出窗分钟 / 窗口截断到 24h 上限。
 */
class CacheMetricsTest {

    /** 可前拨的时钟（分钟桶的时间控制）。 */
    static final class MutableClock extends Clock {
        long millis = 1_800_000_000_000L;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public long millis() {
            return millis;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final CacheMetrics metrics = new CacheMetrics(clock);

    @Test
    void 累计快照_自启动不清零() {
        metrics.hit("retrieval");
        metrics.hit("retrieval");
        metrics.miss("retrieval");
        clock.millis += Duration.ofHours(3).toMillis();
        metrics.hit("retrieval");

        long[] c = metrics.snapshot().get("retrieval");
        assertEquals(3, c[0]);
        assertEquals(1, c[1]);
    }

    @Test
    void 滑动窗口快照_只含在窗分钟() {
        metrics.hit("retrieval");
        clock.millis += Duration.ofMinutes(30).toMillis();
        metrics.hit("retrieval");
        metrics.miss("retrieval");

        long[] all = metrics.snapshot().get("retrieval");
        assertEquals(2, all[0], "累计快照不受时间影响");

        long[] window = metrics.windowSnapshot(Duration.ofMinutes(10)).get("retrieval");
        assertEquals(1, window[0], "30 分钟前的 hit 不在 10 分钟窗口内");
        assertEquals(1, window[1]);

        long[] hour = metrics.windowSnapshot(Duration.ofHours(1)).get("retrieval");
        assertEquals(2, hour[0], "60 分钟窗口包含 30 分钟前的 hit");
    }

    @Test
    void 窗口快照_层间独立() {
        metrics.hit("intent");
        metrics.miss("answer");
        long[] intent = metrics.windowSnapshot(Duration.ofHours(1)).get("intent");
        long[] answer = metrics.windowSnapshot(Duration.ofHours(1)).get("answer");
        assertEquals(1, intent[0]);
        assertEquals(0, intent[1]);
        assertEquals(0, answer[0]);
        assertEquals(1, answer[1]);
    }

    @Test
    void 窗口超24h按上限截断() {
        metrics.hit("retrieval");
        // 前拨 25h：1440 桶环形，25h 前的计数槽已被新纪元覆盖/出窗
        clock.millis += Duration.ofHours(25).toMillis();
        long[] window = metrics.windowSnapshot(Duration.ofHours(30)).get("retrieval");
        assertEquals(0, window[0], "超出 24h 桶容量的请求不可见");
    }

    @Test
    void 同分钟多次命中正确累加() {
        metrics.hit("retrieval");
        metrics.hit("retrieval");
        metrics.hit("retrieval");
        long[] window = metrics.windowSnapshot(Duration.ofMinutes(1)).get("retrieval");
        assertEquals(3, window[0], "同分钟槽内 CAS 累加");
    }
}
