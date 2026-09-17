package com.jjx.customer.platform.cache;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进程内滑动窗口频率统计器单测（可控时钟）：同 key 窗口内递增 / 不同 key 独立 /
 * 窗口滑出清零 / 半窗滑动只留在窗计数 / 功能关闭哨兵 / 超容量清冷 key。
 */
class FrequencyTrackerTest {

    /** 可前拨的时钟（滑动窗口的时间控制）。 */
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
    private final CacheProperties props = new CacheProperties(); // freqWindow 默认 15m

    private FrequencyTracker tracker() {
        return new FrequencyTracker(props, clock);
    }

    @Test
    void 同key窗口内递增() {
        FrequencyTracker t = tracker();
        assertEquals(1, t.observe("k"));
        assertEquals(2, t.observe("k"));
        assertEquals(3, t.observe("k"));
    }

    @Test
    void 不同key独立计数() {
        FrequencyTracker t = tracker();
        t.observe("a");
        t.observe("a");
        assertEquals(1, t.observe("b"), "b 是独立窗口，不受 a 计数影响");
        assertEquals(3, t.observe("a"));
    }

    @Test
    void 窗口滑出后旧计数清零() {
        FrequencyTracker t = tracker();
        t.observe("k");
        t.observe("k");
        // 前拨两个完整窗口：所有旧桶必然出窗（避开整除边界）
        clock.millis += props.getFreqWindow().toMillis() * 2;
        assertEquals(1, t.observe("k"), "旧计数全部滑出窗口，从 1 重新计");
    }

    @Test
    void 半窗滑动只保留在窗计数() {
        FrequencyTracker t = tracker();
        t.observe("k");                                   // t0
        clock.millis += 10 * 60_000L;                     // t0+10m：t0 仍在 15m 窗内
        assertEquals(2, t.observe("k"));
        clock.millis += 10 * 60_000L;                     // t0+20m：t0 出窗，t0+10m 在窗
        assertEquals(2, t.observe("k"));
    }

    @Test
    void 功能关闭返回不可用哨兵() {
        props.setEnabled(false);
        assertEquals(FrequencyTracker.UNAVAILABLE, tracker().observe("k"));
    }

    @Test
    void freqWindow为零同样关闭() {
        props.setFreqWindow(java.time.Duration.ZERO);
        assertEquals(FrequencyTracker.UNAVAILABLE, tracker().observe("k"));
    }

    @Test
    void 超容量先清冷key再注册新key() {
        props.setFreqMaxKeys(2);
        FrequencyTracker t = tracker();
        t.observe("cold1");
        t.observe("cold2");
        // 冷 key 出窗 + 清理节流窗口（首次 sweep 无节流）后，新 key 应挤掉冷 key 注册成功
        clock.millis += props.getFreqWindow().toMillis() * 2;
        long count = t.observe("hot3");
        assertEquals(1, count, "冷 key 被 sweep 清理，hot3 注册成功且从 1 计数");
    }

    @Test
    void 容量内热key不被误清() {
        props.setFreqMaxKeys(2);
        FrequencyTracker t = tracker();
        t.observe("hot1");
        clock.millis += 60_000L;
        t.observe("hot1");                                // 窗口内持续活跃
        t.observe("hot2");
        clock.millis += 60_000L;
        assertEquals(3, t.observe("hot1"), "在窗计数的热 key 不受容量清理影响");
        assertTrue(t.observe("hot2") >= 1);
    }
}
