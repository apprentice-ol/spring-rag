package com.jjx.customer.platform.cache;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 频率决策器单测（纯函数，无 IO）：准入阈值 / fail-open / eval 旁路 / 热度 TTL 档位。
 */
class CacheFrequencyPolicyTest {

    private final CacheFrequencyPolicy policy = new CacheFrequencyPolicy(new CacheProperties());

    // ===== admits =====

    @Test
    void 阈值1恒准许_等于无频率策略() {
        assertTrue(policy.admits(0, 1, false));
        assertTrue(policy.admits(1, 1, false));
    }

    @Test
    void 阈值2_满阈值才准许() {
        assertFalse(policy.admits(1, 2, false));
        assertTrue(policy.admits(2, 2, false));
        assertTrue(policy.admits(9, 2, false));
    }

    @Test
    void 统计不可用_fail_open不限制() {
        assertTrue(policy.admits(FrequencyTracker.UNAVAILABLE, 2, false));
    }

    @Test
    void eval旁路恒准许() {
        assertTrue(policy.admits(1, 2, true));
        assertTrue(policy.admits(0, 5, true));
    }

    // ===== scaledTtl =====

    @Test
    void 未达热度档返回原TTL() {
        Duration base = Duration.ofMinutes(10);
        assertEquals(base, policy.scaledTtl(0, base));
        assertEquals(base, policy.scaledTtl(4, base));
        assertEquals(base, policy.scaledTtl(FrequencyTracker.UNAVAILABLE, base));
    }

    @Test
    void 一档乘hot倍数() {
        Duration base = Duration.ofMinutes(10);
        assertEquals(Duration.ofMinutes(40), policy.scaledTtl(5, base));
        assertEquals(Duration.ofMinutes(40), policy.scaledTtl(19, base));
    }

    @Test
    void 二档顶格() {
        Duration base = Duration.ofMinutes(10);
        assertEquals(Duration.ofMinutes(100), policy.scaledTtl(20, base));
        assertEquals(Duration.ofMinutes(100), policy.scaledTtl(999, base));
    }

    @Test
    void null基准返回null() {
        assertEquals(null, policy.scaledTtl(20, null));
    }
}
