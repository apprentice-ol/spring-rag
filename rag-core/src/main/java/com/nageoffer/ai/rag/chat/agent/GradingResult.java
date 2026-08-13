package com.nageoffer.ai.rag.chat.agent;

import java.util.List;

/**
 * 检索结果相关性评估结果（grade 工具产出，CRAG/Self-RAG 用）。
 *
 * @param grades        逐 chunk 评估
 * @param relevantCount 达到阈值的相关 chunk 数
 * @param avgScore      平均相关性分数（0~1）
 * @param verdict       聚合裁决：ALL_RELEVANT / PARTIAL / ALL_IRRELEVANT
 */
public record GradingResult(
        List<ChunkGrade> grades,
        int relevantCount,
        double avgScore,
        GradeVerdict verdict
) {
    public static GradingResult empty() {
        return new GradingResult(List.of(), 0, 0.0, GradeVerdict.ALL_IRRELEVANT);
    }
}
