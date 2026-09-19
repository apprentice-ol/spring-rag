package com.agentframework.engine.policy;

/**
 * Region 级运行指标：按区域聚合的执行画像。
 *
 * @param regionId        区域 id
 * @param paradigm        范式标签
 * @param executionTimeMs 区域内节点累计耗时
 * @param nodeCount       区域内节点执行次数
 * @param llmCalls        LLM 节点执行次数
 * @param toolCalls       工具节点执行次数
 * @param tokenCount      区域内累计 token 用量
 * @param iterationCount  循环迭代次数（M2.5 起填充）
 * @param approvalCount   人工交互次数
 * @param approvalPendingCount 其中挂起等待人工输入的次数
 */
public record RegionMetrics(
        String regionId,
        String paradigm,
        long executionTimeMs,
        int nodeCount,
        int llmCalls,
        int toolCalls,
        long tokenCount,
        int iterationCount,
        int approvalCount,
        int approvalPendingCount) {

    public RegionMetrics {
        regionId = regionId == null ? "unassigned" : regionId;
        paradigm = paradigm == null ? "" : paradigm;
    }
}
