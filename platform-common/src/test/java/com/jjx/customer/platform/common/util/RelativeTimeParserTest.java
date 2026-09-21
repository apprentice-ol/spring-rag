package com.jjx.customer.platform.common.util;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 相对时间换算的窗口边界语义。
 *
 * <p><b>为什么值得钉</b>：窗口格式是分钟粒度（HH:mm），而「最近N分钟」的 end 是当前时刻——
 * 直接截断会把报错发生后的最近 60 秒系统性切在窗外（真机 2026-09-21：09:20:26 的报错日志
 * vs 窗口 end=09:20:00，差 26 秒反查未命中）。end 必须进位到下一整分，方向错了不会报错，
 * 只会表现为"刚发生的报错查不到"——最难归因的那类缺陷。</p>
 */
class RelativeTimeParserTest {

    /** 2026-09-21 09:20:38（报错发生在 09:20:26 之后的提问时刻）。 */
    private static Clock clockAt(String iso) {
        return Clock.fixed(Instant.parse(iso), ZoneId.of("Asia/Shanghai"));
    }

    @Test
    void 最近N分钟_end进位到下一整分() {
        // now=09:20:38 → 窗口 [09:10, 09:21]：09:20:26 的日志必须落在窗内
        String w = RelativeTimeParser.parse("最近10分钟", clockAt("2026-09-21T01:20:38Z"));
        assertEquals("2026-09-21T09:10~2026-09-21T09:21", w);
    }

    @Test
    void end恰在整分_也进位() {
        // now=09:20:00 → end=09:21：进位是常态而非按需补偿，语义稳定可预期
        String w = RelativeTimeParser.parse("最近1小时", clockAt("2026-09-21T01:20:00Z"));
        assertEquals("2026-09-21T08:20~2026-09-21T09:21", w);
    }

    @Test
    void 点级表达_end同样进位() {
        // 「今天下午3点」= 15:00 ± 30min → [14:30, 15:31]（进位一致，无特例分支）
        String w = RelativeTimeParser.parse("今天下午3点", clockAt("2026-09-21T01:20:38Z"));
        assertEquals("2026-09-21T14:30~2026-09-21T15:31", w);
    }

    @Test
    void 无法识别返回null() {
        assertNull(RelativeTimeParser.parse("随便说点啥", clockAt("2026-09-21T01:20:38Z")));
        assertNull(RelativeTimeParser.parse("", clockAt("2026-09-21T01:20:38Z")));
    }
}
