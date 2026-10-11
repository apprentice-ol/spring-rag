package com.jjx.ai.llmobservability.backends.langfuse.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * Langfuse 数据集条目（黄金数据）。
 *
 * <p>与 Langfuse 公共 API 的 dataset-item 结构对应：{@code input} 为问题，
 * {@code expectedOutput} 为黄金答案，{@code id} 用于关联 run item。</p>
 *
 * @param id              Langfuse 数据集条目 id（关联 run item 时使用）
 * @param status          条目状态（如 ACTIVE）
 * @param input           问题（字符串或结构化 JSON）
 * @param expectedOutput  黄金答案（可为 null）
 * @param metadata        条目附加元数据（可为空 Map）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LangfuseDatasetItem(

        String id,
        String status,
        Object input,
        Object expectedOutput,
        Map<String, Object> metadata
) {
}
