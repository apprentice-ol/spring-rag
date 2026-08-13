package com.nageoffer.ai.rag.chat.agent;

import com.nageoffer.ai.rag.chat.retrieval.MultiChannelRetrievalEngine;
import com.nageoffer.ai.rag.chat.retrieval.RetrievedChunk;

import java.util.List;

/**
 * Agent 编排检索的统一产出。
 *
 * @param finalChunks     喂给 streamRagResponse 的最终上下文块
 * @param lastRetrieval   最近一次 retrieve 的原始通道结果（前端展示通道命中），可空
 * @param trace           执行轨迹（前端对照面板渲染）
 * @param verdict         检索充分性裁决（决定 pipeline 后续分支）
 */
public record AgentRetrievalResult(
        List<RetrievedChunk> finalChunks,
        MultiChannelRetrievalEngine.RetrievalResult lastRetrieval,
        AgentTrace trace,
        RetrievalVerdict verdict
) {
    public static AgentRetrievalResult empty(AgentTrace trace, RetrievalVerdict verdict) {
        return new AgentRetrievalResult(List.of(), null, trace, verdict);
    }

    public boolean isEmpty() {
        return finalChunks == null || finalChunks.isEmpty();
    }
}
