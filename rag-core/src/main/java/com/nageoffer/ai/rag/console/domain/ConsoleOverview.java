package com.nageoffer.ai.rag.console.domain;

/**
 * 控制台总览数据：系统状态 + 业务统计 + 检索/入库配置只读视图。
 */
public record ConsoleOverview(SystemStatus system, Stats stats, RetrievalConfig config) {

    /** 系统状态 */
    public record SystemStatus(String backend, String database, String javaVersion, String appName) {}

    /** 业务统计（-1 表示该表不存在/查询失败） */
    public record Stats(long documents, long chunks, long conversations, long evalRuns, long evalDatasets) {}

    /** 检索/分块/开关/模型 配置快照（只读展示） */
    public record RetrievalConfig(
            int topK,
            double similarityThreshold,
            int recallBudget,
            int candidateLimit,
            int contextTopK,
            int rrfK,
            int chunkSize,
            int chunkOverlap,
            boolean rerankEnabled,
            boolean keywordEnabled,
            boolean webSearchEnabled,
            String chatModel,
            String embeddingModel,
            String rerankModel) {}
}
