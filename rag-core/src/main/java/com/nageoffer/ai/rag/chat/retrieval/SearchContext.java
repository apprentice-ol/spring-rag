package com.nageoffer.ai.rag.chat.retrieval;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 检索上下文，贯穿检索管道的全流程。
 * <p>
 * 包含原始问题、改写后的问题、检索预算、元数据过滤条件等。
 * IntentClassifier 产出的意图信息也可注入 metadata 中供通道决策。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchContext {

    /** 用户原始问题 */
    private String query;

    /** 改写/归一化后的问题 */
    private String rewrittenQuery;

    /** 每条通道召回条数 */
    private int topK;

    /** 相似度阈值（0~1），低于此分数不召回 */
    private double threshold;

    /** 检索预算（三级漏斗控制） */
    private RetrievalBudget budget;

    /** 扩展元数据（意图信息、过滤条件、会话上下文等） */
    private Map<String, Object> metadata;
}
