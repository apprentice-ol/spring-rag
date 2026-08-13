package com.nageoffer.ai.rag.chat.retrieval;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 检索命中的文档片段。
 * <p>
 * 封装从向量库、关键词、联网等通道检索到的文档片段，
 * 包含内容、分数、元数据以及来源通道信息。
 * </p>
 */
@Data
@AllArgsConstructor
public class RetrievedChunk {

    /** 文档片段文本内容 */
    private String content;

    /** 相关性分数（融合/重排后的分数，用于排序；如 RRF 分） */
    private Double score;

    /** 原始相似度（通道返回的 cosine，融合前；展示用，不受 RRF/rerank 重排影响） */
    private Double originalScore;

    /** 文档元数据（来源文档 ID、chunk 索引、文件名等） */
    private Map<String, Object> metadata;

    /** 来源检索通道类型 */
    private SearchChannelType channelType;

    /** 在最终结果中的排序序号 */
    private Integer rank;

    public RetrievedChunk(String content, Double score, Map<String, Object> metadata, SearchChannelType channelType) {
        this.content = content;
        this.score = score;
        this.metadata = metadata;
        this.channelType = channelType;
    }
}
