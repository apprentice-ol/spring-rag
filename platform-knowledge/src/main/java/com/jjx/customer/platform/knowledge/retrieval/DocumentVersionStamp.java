package com.jjx.customer.platform.knowledge.retrieval;
import com.jjx.customer.platform.cache.*;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 文档版本号（docver）：缓存失效的版本化方案——不做前缀扫删（KEYS scan 是 Redis 反模式），
 * 入库完成时 INCR 版本号，检索/答案缓存的 key 拼入当前版本，版本一变新请求自然写新 key，
 * 旧 key 等 TTL 漂走。
 * <p>{@link #bump} 双写：collectionId 对应键 + {@link #ALL_COLLECTIONS} 哨兵键——
 * 检索存在全库模式（collectionId=null），独立文档入库同样会改变全库结果。
 * <p>{@link #current} 返回 -1 表示 Redis 不可用，调用方应整体旁路缓存（不读不写）。
 */
@Slf4j
@Component
public class DocumentVersionStamp {

    /** 全库模式（collectionId=null）的哨兵集合键 */
    public static final String ALL_COLLECTIONS = "_all";

    private final StringRedisTemplate redis;
    private final RedisHealth redisHealth;

    public DocumentVersionStamp(StringRedisTemplate redis, RedisHealth redisHealth) {
        this.redis = redis;
        this.redisHealth = redisHealth;
    }

    /** 当前版本（无记录=1）；Redis 不可用返回 -1（调用方旁路缓存）。 */
    public long current(String collectionKey) {
        if (redisHealth.isDown()) {
            return -1L;
        }
        try {
            String v = redis.opsForValue().get(CacheKeys.docverKey(collectionKey));
            redisHealth.onSuccess();
            return v == null ? 1L : Long.parseLong(v);
        } catch (Exception e) {
            redisHealth.onFailure();
            log.warn("[DocVer] 读取文档版本失败，本轮缓存旁路: {}", e.getMessage());
            return -1L;
        }
    }

    /** 入库完成钩子：collectionId 键与全库哨兵键都 INCR（自吞异常，失败仅损失失效精度，TTL 兜底）。 */
    public void bump(Long collectionId) {
        if (redisHealth.isDown()) {
            return; // 降级期不碰 Redis；损失一次失效精度，TTL 与重灌幂等清理兜底
        }
        try {
            redis.opsForValue().increment(CacheKeys.docverKey(ALL_COLLECTIONS));
            if (collectionId != null) {
                redis.opsForValue().increment(CacheKeys.docverKey(String.valueOf(collectionId)));
            }
            redisHealth.onSuccess();
        } catch (Exception e) {
            redisHealth.onFailure();
            log.warn("[DocVer] 版本号 INCR 失败（忽略，TTL 兜底）: {}", e.getMessage());
        }
    }
}
