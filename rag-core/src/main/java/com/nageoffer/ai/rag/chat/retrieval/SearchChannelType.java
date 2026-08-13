package com.nageoffer.ai.rag.chat.retrieval;

/**
 * 检索通道类型枚举。
 * <p>
 * 标识不同检索通道的来源类型，用于多通道结果融合时的权重分配和溯源标记。
 * </p>
 */
public enum SearchChannelType {

    /** 向量检索（语义相似度搜索，Spring AI VectorStore） */
    VECTOR,

    /** 关键词检索（PG 全文检索 / BM25） */
    KEYWORD,

    /** 图谱检索（LightRAG / GraphRAG） */
    GRAPH,

    /** 联网检索（Web Search API） */
    WEB_SEARCH
}
