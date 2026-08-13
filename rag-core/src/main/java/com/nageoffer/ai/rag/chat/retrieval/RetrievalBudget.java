package com.nageoffer.ai.rag.chat.retrieval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * 检索预算，控制检索管道的三级漏斗数量。
 * <p>
 * 多通道检索中，各通道先按 recallBudget 召回 → 后处理器（去重+融合）按 candidateLimit 截断 →
 * Rerank 精排后按 contextTopK 输出最终结果给 LLM。
 * </p>
 */
@Data
@Builder
@AllArgsConstructor
public class RetrievalBudget {

    /** 每通道召回上限（粗召回阶段），默认 20 */
    @Builder.Default
    private int recallBudget = 20;

    /** 融合后送入 Rerank 的候选上限，默认 15 */
    @Builder.Default
    private int candidateLimit = 15;

    /** Rerank 后最终输出给 LLM 的条数，默认 5 */
    @Builder.Default
    private int contextTopK = 5;
}
