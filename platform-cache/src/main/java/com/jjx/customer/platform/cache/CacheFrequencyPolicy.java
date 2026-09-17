package com.jjx.customer.platform.cache;

import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 频率缓存决策器（纯函数、无状态）：拿 {@link FrequencyTracker} 的窗口计数做两个决定——
 * ① 是否准许写入（准入：窗口内出现满阈值才写，防长尾一次性查询污染缓存）；
 * ② 热度 TTL（延长：窗口计数达档位则放大 TTL，只延长不缩短）。
 * <p>与统计器分离是刻意的单一职责切分：本类不碰 Redis、不发 IO，全逻辑可离线单测。
 * <p>安全性前提：本缓存体系的 key 均含 docver 版本号（文档重灌必然换 key），TTL 只是内存回收
 * 旋钮而非正确性旋钮——热 key TTL 放大不会吐旧数据。
 */
@Component
public class CacheFrequencyPolicy {

    private final CacheProperties props;

    public CacheFrequencyPolicy(CacheProperties props) {
        this.props = props;
    }

    /**
     * 是否准许写入缓存。
     *
     * @param windowCount 窗口计数（{@link FrequencyTracker#UNAVAILABLE} = 统计不可用 → 不限制）
     * @param threshold   本层准入阈值（{@code <=1} = 无准入，即频率策略关闭前的行为）
     * @param bypass      强制放行（eval 跑批：参数扫描下每个参数组合都是新 key，频率永远不满，
     *                    不旁路则 eval 提速归零）
     */
    public boolean admits(long windowCount, int threshold, boolean bypass) {
        if (bypass || threshold <= 1 || windowCount == FrequencyTracker.UNAVAILABLE) {
            return true;
        }
        return windowCount >= threshold;
    }

    /**
     * 热度 TTL：窗口计数达一档 ×hot-multiplier、达二档 ×max-ttl-multiplier，未达档返回 baseTtl（不动）。
     * 统计不可用（UNAVAILABLE）按未达档处理。
     */
    public Duration scaledTtl(long windowCount, Duration baseTtl) {
        if (baseTtl == null || windowCount == FrequencyTracker.UNAVAILABLE) {
            return baseTtl;
        }
        if (windowCount >= props.getHotterThreshold()) {
            return baseTtl.multipliedBy(props.getMaxTtlMultiplier());
        }
        if (windowCount >= props.getHotThreshold()) {
            return baseTtl.multipliedBy(props.getHotMultiplier());
        }
        return baseTtl;
    }
}
