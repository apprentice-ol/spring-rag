package com.jjx.ai.llmobservability.backends.langfuse.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Langfuse 数据集 run item 关联请求。
 *
 * <p>对应公共 API {@code POST /api/public/dataset-run-items} 的请求体。
 * 通过 runName + datasetItemId + traceId（+ observationId）把黄金数据与模型运行记录关联。</p>
 *
 * @param runName        数据集 run 名（实验名；不存在时自动创建）
 * @param datasetItemId  黄金数据集条目 id
 * @param traceId        模型运行 trace id
 * @param observationId  模型回答所在 observation id（可为 null，缺省时取 trace 级输出）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LangfuseRunItemLink(
        String runName,
        String datasetItemId,
        String traceId,
        String observationId
) {
}
