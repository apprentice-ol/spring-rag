package com.agentframework.crosscutting.metrics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存指标实现：计数器累加、仪表保留最新值、直方图保留全部采样。
 *
 * <p>用于单进程观测与单元测试；生产环境请接入外部指标系统。</p>
 */
public final class InMemoryMetrics implements Metrics {

    private final Map<String, Long> counters = new ConcurrentHashMap<>();
    private final Map<String, Double> gauges = new ConcurrentHashMap<>();
    private final List<MetricSample> samples = new ArrayList<>();
    private final List<MetricExporter> exporters = new ArrayList<>();

    /**
     * 注册导出器。
     *
     * @param exporter 导出器
     * @return 当前实例
     */
    public InMemoryMetrics addExporter(MetricExporter exporter) {
        if (exporter != null) {
            synchronized (exporters) {
                exporters.add(exporter);
            }
        }
        return this;
    }

    @Override
    public void counter(String name, long delta, Map<String, Object> tags) {
        counters.merge(name, delta, Long::sum);
        record(new MetricSample(name, MetricType.COUNTER, delta, tags, null));
    }

    @Override
    public void gauge(String name, double value, Map<String, Object> tags) {
        gauges.put(name, value);
        record(new MetricSample(name, MetricType.GAUGE, value, tags, null));
    }

    @Override
    public void histogram(String name, double value, Map<String, Object> tags) {
        record(new MetricSample(name, MetricType.HISTOGRAM, value, tags, null));
    }

    @Override
    public List<MetricSample> snapshot() {
        synchronized (samples) {
            return List.copyOf(samples);
        }
    }

    /**
     * @param name 计数器名
     * @return 累计值，未记录返回 0
     */
    public long counterValue(String name) {
        return counters.getOrDefault(name, 0L);
    }

    /**
     * @param name 仪表名
     * @return 最新值，未记录返回 null
     */
    public Double gaugeValue(String name) {
        return gauges.get(name);
    }

    /**
     * @param name 指标名
     * @return 该指标下全部采样值
     */
    public List<Double> values(String name) {
        List<Double> values = new ArrayList<>();
        snapshot().stream().filter(sample -> sample.name().equals(name)).forEach(sample -> values.add(sample.value()));
        return values;
    }

    /**
     * 推送全部采样点到导出器。
     */
    public void flush() {
        List<MetricSample> batch = snapshot();
        synchronized (exporters) {
            for (MetricExporter exporter : exporters) {
                exporter.export(batch);
            }
        }
    }

    /**
     * @return 计数器视图
     */
    public Map<String, Long> counterView() {
        return Map.copyOf(new LinkedHashMap<>(counters));
    }

    /** 记录采样点并同步推送导出器。 */
    private void record(MetricSample sample) {
        synchronized (samples) {
            samples.add(sample);
        }
    }
}
