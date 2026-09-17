package com.jjx.customer.platform.cache;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redis 断路器状态机单测（冷却用毫秒级配置加速）：连续失败跳 OPEN → 冷却期满半开 →
 * 探测成功回 CLOSED / 失败回 OPEN 再等冷却。
 */
class RedisHealthTest {

    private RedisHealth health(int threshold, long cooldownMs) {
        CacheProperties props = new CacheProperties();
        props.getCircuit().setFailureThreshold(threshold);
        props.getCircuit().setCooldown(Duration.ofMillis(cooldownMs));
        return new RedisHealth(props, null);
    }

    @Test
    void 连续失败满阈值跳OPEN() {
        RedisHealth h = health(3, 60_000);
        assertFalse(h.isDown());
        h.onFailure();
        h.onFailure();
        assertFalse(h.isDown(), "未满阈值不应降级");
        h.onFailure();
        assertTrue(h.isDown(), "连续 3 次失败应 OPEN");
    }

    @Test
    void 成功清零连续计数() {
        RedisHealth h = health(3, 60_000);
        h.onFailure();
        h.onFailure();
        h.onSuccess();
        h.onFailure();
        h.onFailure();
        assertFalse(h.isDown(), "中间成功应清零计数");
    }

    @Test
    void 冷却期满转半开_探测成功回CLOSED() throws InterruptedException {
        RedisHealth h = health(1, 50);
        h.onFailure();
        assertTrue(h.isDown());
        Thread.sleep(80);
        // isDown 触发状态转 HALF_OPEN（仍视为 down，放探测）
        assertTrue(h.isDown());
        h.onSuccess();
        assertFalse(h.isDown(), "探测成功应恢复");
    }

    @Test
    void 半开探测失败回OPEN_再等冷却() throws InterruptedException {
        RedisHealth h = health(1, 50);
        h.onFailure();
        Thread.sleep(80);
        assertTrue(h.isDown()); // 转 HALF_OPEN
        h.onFailure();          // 探测失败
        Thread.sleep(20);       // 远小于冷却
        assertTrue(h.isDown(), "探测失败后仍在 OPEN 冷却中");
        Thread.sleep(80);
        assertTrue(h.isDown()); // 再次转 HALF_OPEN 放探测
        h.onSuccess();
        assertFalse(h.isDown());
    }

    @Test
    void 成功上报在CLOSED下无副作用() {
        RedisHealth h = health(3, 60_000);
        h.onSuccess();
        h.onSuccess();
        assertFalse(h.isDown());
    }
}
