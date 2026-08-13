package com.nageoffer.ai.rag.chat.intent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * 意图分类结果。
 * <p>
 * 包含意图类型、置信度和是否需要检索的决策标记。
 * </p>
 */
@Data
@Builder
@AllArgsConstructor
public class IntentResult {

    /** 意图类型：knowledge_query / greeting / chitchat */
    private String intent;

    /** LLM 给出的置信度分数（0~1） */
    private double confidence;

    /** 是否需要执行知识库检索 */
    private boolean needsRetrieval;

    /** 是否需要联网搜索 */
    private boolean needsWebSearch;

    /** 是否走日志诊断（排查报错/异常/traceId） */
    private boolean needsDiagnose;

    /** 分类理由说明 */
    private String reason;
}
