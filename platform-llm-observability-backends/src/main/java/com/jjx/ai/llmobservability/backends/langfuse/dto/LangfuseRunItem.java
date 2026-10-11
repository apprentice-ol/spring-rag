package com.jjx.ai.llmobservability.backends.langfuse.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Langfuse 数据集 run item（关联的结果）。
 *
 * <p>run item 是“黄金数据 ↔ 模型运行记录”的关联点：同时持有
 * {@code datasetItemId}（黄金数据）与 {@code traceId}/{@code observationId}（模型回答）。</p>
 *
 * @param id              run item id
 * @param datasetRunId    所属数据集 run id
 * @param datasetRunName  所属数据集 run 名
 * @param datasetItemId   黄金数据集条目 id
 * @param traceId         模型运行 trace id
 * @param observationId   模型回答所在 observation id（可为 null）
 * @param createdAt       创建时间（ISO-8601 字符串）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LangfuseRunItem(
        String id,
        String datasetRunId,
        String datasetRunName,
        String datasetItemId,
        String traceId,
        String observationId,
        String createdAt
) {
}
