package com.jjx.ai.llmobservability.backends.langfuse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 提交到 Langfuse 的评分请求。
 *
 * <p>对应公共 API {@code POST /api/public/scores} 的请求体。{@code datasetRunId} 与
 * {@code traceId} 至少提供一个；{@code observationId} 用于把分数挂到具体 observation。</p>
 *
 * @param name          评分名（如 Answer Correctness）
 * @param value         数值分数
 * @param comment       评分理由（可为 null）
 * @param datasetRunId  数据集 run id（可为 null）
 * @param traceId       模型运行 trace id（可为 null）
 * @param observationId 模型回答 observation id（可为 null）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LangfuseScoreSubmission(
        String name,
        double value,
        String comment,
        String datasetRunId,
        String traceId,
        String observationId
) {
}
