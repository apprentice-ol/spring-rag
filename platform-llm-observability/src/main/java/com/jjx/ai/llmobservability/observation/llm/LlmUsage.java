package com.jjx.ai.llmobservability.observation.llm;

/**
 * 一次 LLM 调用的 token 用量（框架无关，对齐 OTel gen_ai.usage.* 语义）。
 *
 * @param inputTokens  输入 token 数
 * @param outputTokens 输出 token 数
 * @param totalTokens  总 token 数
 */
public record LlmUsage(Integer inputTokens, Integer outputTokens, Integer totalTokens) {

    /** 由输入/输出 token 构造，自动求和 total。 */
    public static LlmUsage of(Integer inputTokens, Integer outputTokens) {
        int in = inputTokens == null ? 0 : inputTokens;
        int out = outputTokens == null ? 0 : outputTokens;
        return new LlmUsage(in, out, in + out);
    }
}
