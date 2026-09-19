package com.agentframework.crosscutting.metrics;

/**
 * 指标导出扩展点：把采样点推送到 Prometheus、日志或自研平台。
 */
@FunctionalInterface
public interface MetricExporter {

    /**
     * 导出一批采样点。
     *
     * @param samples 采样点列表
     */
    void export(java.util.List<MetricSample> samples);
}
