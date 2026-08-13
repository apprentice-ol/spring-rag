package com.nageoffer.ai.rag.chat.agent;

/**
 * 单个 chunk 的相关性评估结果（grade 工具产出，CRAG/Self-RAG 用）。
 *
 * @param chunkRef chunk 在 RetrievalWorkspace 中的 ref 编号
 * @param score    相关性分数（0~1）
 * @param relevant 是否达到阈值（score >= gradeThreshold）
 * @param reason   LLM 给出的判断理由
 */
public record ChunkGrade(int chunkRef, double score, boolean relevant, String reason) {
}
