package com.nageoffer.ai.rag.chat.rerank;

import com.nageoffer.ai.rag.chat.retrieval.RetrievedChunk;
import java.util.List;

/**
 * Rerank 客户端接口。
 * <p>
 * 对检索到的文档片段进行精排，按与查询的相关度重新排序后返回 topN 条。
 * 当前实现为百炼（BaiLian）Rerank API，支持替换为其他服务商。
 * </p>
 */
public interface RerankClient {

    /** 获取 Rerank 服务提供商名称 */
    String provider();

    /**
     * 对候选文档片段进行精排。
     *
     * @param query      用户查询文本
     * @param candidates 待排序的候选文档片段
     * @param topN       返回前 N 个最相关的结果
     * @return 重新排序后的文档片段列表，按相关性从高到低
     */
    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN);
}
