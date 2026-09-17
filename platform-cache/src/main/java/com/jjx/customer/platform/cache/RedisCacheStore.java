package com.jjx.customer.platform.cache;

import com.jjx.ai.llmobservability.observation.TelemetryTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Redis 缓存存取实现（StringRedisTemplate + 原生 TTL）。
 * <p>全操作 try-catch 降级：Redis 挂掉 = 全量 miss + 静默写失败，主链路零感知；
 * 故障 warn 按 60s 节流（避免日志风暴）。命中/未命中经 {@link TelemetryTemplate#tag}
 * 打到当前 span（best-effort，无活跃 span 时丢弃）。
 */
@Slf4j
@Component
public class RedisCacheStore implements CacheStore {

    /** Redis 故障 warn 节流间隔 */
    private static final long WARN_THROTTLE_MS = 60_000L;

    private final StringRedisTemplate redis;
    private final CacheProperties cacheProperties;
    private final TelemetryTemplate obsTemplate;
    private final RedisHealth redisHealth;
    private final CacheMetrics cacheMetrics;

    private volatile long lastWarnAt = 0L;

    public RedisCacheStore(StringRedisTemplate redis,
                           CacheProperties cacheProperties,
                           TelemetryTemplate obsTemplate,
                           RedisHealth redisHealth,
                           CacheMetrics cacheMetrics) {
        this.redis = redis;
        this.cacheProperties = cacheProperties;
        this.obsTemplate = obsTemplate;
        this.redisHealth = redisHealth;
        this.cacheMetrics = cacheMetrics;
    }

    @Override
    public Optional<String> get(String key, String layer) {
        if (!cacheProperties.isEnabled() || redisHealth.isDown()) {
            return Optional.empty();
        }
        try {
            String value = redis.opsForValue().get(key);
            redisHealth.onSuccess();
            if (value != null) {
                cacheMetrics.hit(layer);
            } else {
                cacheMetrics.miss(layer);
            }
            tag(value != null ? "cache.hit" : "cache.miss", layer);
            return Optional.ofNullable(value);
        } catch (Exception | StackOverflowError e) {
            // StackOverflowError 一并兜住（Redisson 适配层版本错位可抛 Error，穿透会打死线程）
            redisHealth.onFailure();
            warnThrottled("get", e);
            tag("cache.miss", layer);
            return Optional.empty();
        }
    }

    @Override
    public void put(String key, String json, Duration ttl, String layer) {
        if (!cacheProperties.isEnabled() || redisHealth.isDown()) {
            return;
        }
        try {
            redis.opsForValue().set(key, json, ttl);
            redisHealth.onSuccess();
        } catch (Exception | StackOverflowError e) {
            redisHealth.onFailure();
            warnThrottled("put", e);
        }
    }

    @Override
    public void touch(String key, Duration ttl, String layer) {
        if (!cacheProperties.isEnabled() || redisHealth.isDown()) {
            return;
        }
        try {
            // 老重载走 EXPIRE 秒路径；expire(key, Duration) 的 pExpire 毫秒路径在 Redisson
            // 适配器未实现时自我递归 StackOverflowError（同 FrequencyTracker 注释）
            redis.expire(key, Math.max(1, ttl.toSeconds()), TimeUnit.SECONDS);
            redisHealth.onSuccess();
        } catch (Exception | StackOverflowError e) {
            redisHealth.onFailure();
            warnThrottled("touch", e);
        }
    }

    private void tag(String name, String layer) {
        try {
            obsTemplate.tag(name, layer);
        } catch (Exception ignore) {
            // 打点 best-effort，绝不影响缓存读写
        }
    }

    private void warnThrottled(String op, Throwable e) {
        long now = System.currentTimeMillis();
        if (now - lastWarnAt >= WARN_THROTTLE_MS) {
            lastWarnAt = now;
            log.warn("[CacheStore] Redis {} 失败（60s 内同类不重复告警），缓存降级为 miss: {}", op, e.getMessage());
        }
    }
}
