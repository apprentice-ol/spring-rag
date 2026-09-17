package com.jjx.customer.platform.agent.framework.result;

/**
 * 回交管线的生成规格（"全给"语义）：管线只做执行，不回查任何全局状态。
 *
 * <p>为什么给内容而不是只给 key：只给 key 会让管线取到"当前最新版"，与 trace、缓存 key、
 * eval 复现不是同一份内容，"改 prompt 缓存自动失效"的闭环会断。</p>
 *
 * @param answerPromptKey     答案 prompt 的 key
 * @param answerPromptContent 该 key 本次实际使用的内容快照
 * @param assembledContextText 已排序/编号、可直接喂模型的上下文文本
 * @param fingerprint         执行指纹（与 ExecutionResult 一致）
 */
public record GenerationSpec(String answerPromptKey,
                             String answerPromptContent,
                             String assembledContextText,
                             ExecutionFingerprint fingerprint) {
}
