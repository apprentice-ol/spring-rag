package com.nageoffer.ai.rag.chat.agent;

/** 检索结果相关性评估的聚合裁决（CRAG/Self-RAG 三档路由用）。 */
public enum GradeVerdict {
    /** 全部 chunk 相关 → 直接用于回答 */
    ALL_RELEVANT,
    /** 部分相关 → 取相关子集，可补检索/rerank */
    PARTIAL,
    /** 全部无关 → 触发重试改写 / web 兜底 */
    ALL_IRRELEVANT
}
