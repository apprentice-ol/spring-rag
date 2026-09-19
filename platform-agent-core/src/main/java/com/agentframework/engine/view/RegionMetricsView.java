package com.agentframework.engine.view;

import com.agentframework.engine.policy.RegionMetrics;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 区域指标视图。
 *
 * @param regionId            区域 id
 * @param paradigm            范式标签
 * @param nodeCount           节点执行次数
 * @param llmCalls            LLM 调用次数
 * @param toolCalls           工具调用次数
 * @param tokenCount          累计 token
 * @param executionTimeMs     累计耗时
 * @param iterationCount      循环迭代数
 * @param approvalCount       人工交互次数
 * @param approvalPendingCount 挂起等待次数
 */
public record RegionMetricsView(
        String regionId,
        String paradigm,
        int nodeCount,
        int llmCalls,
        int toolCalls,
        long tokenCount,
        long executionTimeMs,
        int iterationCount,
        int approvalCount,
        int approvalPendingCount) {

    /**
     * @param metrics 运行指标
     * @return 视图
     */
    public static RegionMetricsView of(RegionMetrics metrics) {
        return new RegionMetricsView(metrics.regionId(), metrics.paradigm(), metrics.nodeCount(), metrics.llmCalls(),
                metrics.toolCalls(), metrics.tokenCount(), metrics.executionTimeMs(), metrics.iterationCount(),
                metrics.approvalCount(), metrics.approvalPendingCount());
    }

    /** @return 可序列化文档 */
    public Map<String, Object> toDocument() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("regionId", regionId);
        document.put("paradigm", paradigm);
        document.put("nodeCount", nodeCount);
        document.put("llmCalls", llmCalls);
        document.put("toolCalls", toolCalls);
        document.put("tokenCount", tokenCount);
        document.put("executionTimeMs", executionTimeMs);
        document.put("iterationCount", iterationCount);
        document.put("approvalCount", approvalCount);
        document.put("approvalPendingCount", approvalPendingCount);
        return document;
    }
}
