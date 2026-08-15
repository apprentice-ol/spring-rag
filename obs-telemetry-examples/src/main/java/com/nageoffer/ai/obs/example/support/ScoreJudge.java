package com.nageoffer.ai.obs.example.support;

/**
 * 判分器：比较模型回答与黄金答案，产出分数与理由。
 *
 * <p>生产实现通常是调用 DeepSeek 等 LLM 的 judge 提示词，要求输出 JSON 分数。</p>
 */
@FunctionalInterface
public interface ScoreJudge {

    /**
     * 对一次回答进行判分。
     *
     * @param question        问题
     * @param answer          模型回答
     * @param expectedOutput  黄金答案（可为 null）
     * @return 判分结果
     */
    ScoreVerdict judge(String question, String answer, String expectedOutput);
}
