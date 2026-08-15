package com.nageoffer.ai.obs.example.support;

/**
 * LLM 判分结果。
 *
 * <p>由应用的打分器（如 DeepSeek judge）产出，示例编排将其写入 Langfuse 评分。</p>
 *
 * @param value   数值分数（如 0~1 的正确性得分）
 * @param comment 评分理由
 */
public record ScoreVerdict(
        double value,
        String comment
) {
}
