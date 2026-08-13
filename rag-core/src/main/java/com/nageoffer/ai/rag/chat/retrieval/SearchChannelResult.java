package com.nageoffer.ai.rag.chat.retrieval;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * 检索通道结果。
 * <p>
 * 封装单个检索通道的搜索结果，包含通道信息、命中文档片段列表、执行耗时等。
 * 多通道检索时，每个通道返回一个 SearchChannelResult，最终由 FusionPostProcessor 融合。
 * </p>
 */
@Data
@Builder
@AllArgsConstructor
public class SearchChannelResult {

    /** 通道类型 */
    private SearchChannelType channelType;

    /** 通道名称，如 "vector"、"keyword" */
    private String channelName;

    /** 检索命中的文档片段列表，按相关性降序 */
    private List<RetrievedChunk> chunks;

    /** 通道执行耗时（毫秒） */
    private long latencyMs;

    /** 通道执行过程中的元数据（如检索总量、过滤条件等） */
    private java.util.Map<String, Object> metadata;
}
