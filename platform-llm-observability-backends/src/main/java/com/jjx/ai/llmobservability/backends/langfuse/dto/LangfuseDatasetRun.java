package com.jjx.ai.llmobservability.backends.langfuse.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * Langfuse 数据集 run（实验）。
 *
 * <p>一个 run 把同一批黄金数据的一次完整执行组织在一起；run item 的
 * {@code datasetRunId} 即来自此处，写评分时用于定位实验。</p>
 *
 * @param id           run id（评分写入时作为 datasetRunId）
 * @param name         run 名（如 liveRAG-naive-20260815）
 * @param description  run 描述（可为 null）
 * @param metadata     run 元数据（可为空 Map）
 * @param datasetId    所属数据集 id
 * @param datasetName  所属数据集名
 * @param createdAt    创建时间（ISO-8601 字符串）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LangfuseDatasetRun(
        String id,
        String name,
        String description,
        Map<String, Object> metadata,
        String datasetId,
        String datasetName,
        String createdAt
) {
}
