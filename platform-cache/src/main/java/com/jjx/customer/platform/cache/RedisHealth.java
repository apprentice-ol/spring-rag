package com.jjx.customer.platform.cache;

import com.jjx.ai.llmobservability.observation.TelemetryTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redis 共享断路器（Hystrix 熔断联动降级的朴素版）：RedisCacheStore / DocumentVersionStamp
 * 两处共用一个状态机，避免各自试错叠加等待（FrequencyTracker 已进程内化，不再参战）。
 *
 * <p>状态机：CLOSED（正常）→ 连续失败 ≥ failure-threshold → OPEN（冷却 cooldown 期间所有调用方
 * 直接旁路，零 Redis 调用——慢死形态下每请求 7-8 次操作 × 3s 超时的无效等待就此归零）→
 * 冷却期满 HALF_OPEN（放一次探测请求）→ 成功回 CLOSED / 失败回 OPEN（再等一轮冷却）。
 * 恢复刻意走半开探测而非瞬间全通（TCP 拥塞控制 AIMD 的教训：防恢复瞬间二次踩踏）。
 *
 * <p>线程模型：volatile 状态 + AtomicInteger 计数，尽力而为的同步（并发探测可能多放几个，
 * 无害——探测请求本身就是普通业务调用，失败只是回 OPEN）。类/方法非 final 供测试 Scripted。
 */
@Slf4j
@Component
public class RedisHealth {

    private enum State { CLOSED, OPEN, HALF_OPEN }

    private final CacheProperties cacheProperties;
    private final TelemetryTemplate obsTemplate;

    private volatile State state = State.CLOSED;
    private volatile long openedAtMs = 0L;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    public RedisHealth(CacheProperties cacheProperties, TelemetryTemplate obsTemplate) {
        this.cacheProperties = cacheProperties;
        this.obsTemplate = obsTemplate;
    }

    /** 是否处于降级（OPEN 或冷却期满转 HALF_OPEN 待探测）。调用方据此直接旁路，不碰 Redis。 */
    public boolean isDown() {
        if (state == State.OPEN && System.currentTimeMillis() - openedAtMs >= cooldownMs()) {
            state = State.HALF_OPEN; // 冷却期满：放探测
        }
        return state != State.CLOSED;
    }

    /** 当前状态名（看板用）：CLOSED / OPEN / HALF_OPEN。先过 isDown() 让冷却期满的状态如实翻转。 */
    public String state() {
        isDown();
        return state.name();
    }

    /** 操作成功上报：任何状态回 CLOSED，计数清零。 */
    public void onSuccess() {
        if (state != State.CLOSED) {
            transition(State.CLOSED, "恢复（探测成功），缓存层回效");
        }
        consecutiveFailures.set(0);
    }

    /** 操作失败上报：CLOSED 下累计连续失败，满阈值跳 OPEN；HALF_OPEN 探测失败直接回 OPEN。 */
    public void onFailure() {
        if (state == State.HALF_OPEN) {
            trip("半开探测失败，继续降级");
            return;
        }
        if (consecutiveFailures.incrementAndGet() >= failureThreshold()) {
            trip("连续失败 " + consecutiveFailures.get() + " 次");
        }
    }

    private void trip(String reason) {
        if (state == State.OPEN) {
            openedAtMs = System.currentTimeMillis(); // 刷新冷却起点
            return;
        }
        transition(State.OPEN, reason);
    }

    private void transition(State target, String reason) {
        state = target;
        openedAtMs = System.currentTimeMillis();
        consecutiveFailures.set(0);
        try {
            obsTemplate.tag("cache.circuit", target == State.CLOSED ? "closed" : "open");
        } catch (Exception ignore) {
            // 打点 best-effort
        }
        log.warn("[RedisHealth] 断路器 → {}（{}），cooldown={}s，failure-threshold={}",
                target, reason, cooldownMs() / 1000, failureThreshold());
    }

    private int failureThreshold() {
        CacheProperties.Circuit c = cacheProperties.getCircuit();
        return c != null ? Math.max(1, c.getFailureThreshold()) : 5;
    }

    private long cooldownMs() {
        CacheProperties.Circuit c = cacheProperties.getCircuit();
        return c != null && c.getCooldown() != null ? c.getCooldown().toMillis() : 30_000L;
    }
}
