package com.nageoffer.ai.rag.chat.agent;

/**
 * Agent 对本次检索充分性的裁决，决定 StreamChatPipeline 后续走哪个分支。
 * <p>对齐改造前的分支语义，保证 NaiveRagAgent 零行为差异。
 */
public enum RetrievalVerdict {
    /** 检索充分 → 进入 streamRagResponse（等价改造前的非空分支） */
    READY,
    /** 无结果 → 走 handleRetrievalEmpty（等价改造前的 isEmpty 分支） */
    EMPTY,
    /** 做过重试/兜底但仅部分改善 → 仍进回答，前端标注"降级" */
    DEGRADED
}
