package com.agentframework.crosscutting.metrics;

import java.util.List;
import java.util.Map;

/**
 * 指标采集接口：记录执行链上的计数、瞬时值与分布。
 */
public interface Metrics {

    /**
     * 累加计数器。
     *
     * @param name  指标名
     * @param delta 增量
     * @param tags  标签
     */
    void counter(String name, long delta, Map<String, Object> tags);

    /**
     * 记录瞬时值。
     *
     * @param name  指标名
     * @param value 值
     * @param tags  标签
     */
    void gauge(String name, double value, Map<String, Object> tags);

    /**
     * 记录分布值（例如耗时毫秒）。
     *
     * @param name  指标名
     * @param value 值
     * @param tags  标签
     */
    void histogram(String name, double value, Map<String, Object> tags);

    /** @return 当前全部采样点 */
    List<MetricSample> snapshot();

    /**
     * 计数便捷方法（无标签）。
     *
     * @param name 指标名
     */
    default void counter(String name) {
        counter(name, 1L, Map.of());
    }
}
