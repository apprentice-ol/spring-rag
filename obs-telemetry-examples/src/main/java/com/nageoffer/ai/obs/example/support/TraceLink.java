package com.nageoffer.ai.obs.example.support;

/**
 * 模型运行记录定位信息。
 *
 * <p>由应用在“跑完一次 RAG 问答”后提供：traceId 与（可选的）回答所在 observationId，
 * 供示例编排把模型回答关联到数据集 run item。</p>
 *
 * @param traceId       模型运行 trace id
 * @param observationId 模型回答所在 observation id（可为 null，缺省时取 trace 级输出）
 */
public record TraceLink(
        String traceId,
        String observationId
) {
}
