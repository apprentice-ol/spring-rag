package com.agentframework.infra.modelgateway;

/**
 * token 用量。
 *
 * @param promptTokens     输入 token 数
 * @param completionTokens 输出 token 数
 */
public record Usage(int promptTokens, int completionTokens) {

    /** @return 全零用量 */
    public static Usage zero() {
        return new Usage(0, 0);
    }

    /**
     * @param promptTokens     输入 token 数
     * @param completionTokens 输出 token 数
     * @return 用量对象
     */
    public static Usage of(int promptTokens, int completionTokens) {
        return new Usage(promptTokens, completionTokens);
    }

    /** @return 总 token 数 */
    public int total() {
        return promptTokens + completionTokens;
    }

    /**
     * @param other 另一次用量
     * @return 累加结果
     */
    public Usage plus(Usage other) {
        if (other == null) {
            return this;
        }
        return new Usage(promptTokens + other.promptTokens, completionTokens + other.completionTokens);
    }

    /**
     * 按字符数粗略估算 token（约 4 字符 1 token），用于未返回用量的提供方。
     *
     * @param text 文本
     * @return 估算 token 数
     */
    public static int estimate(String text) {
        return text == null ? 0 : Math.max(1, text.length() / 4);
    }
}
