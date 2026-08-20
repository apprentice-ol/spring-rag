package com.nageoffer.ai.rag.eval.domain;

import java.util.List;

/**
 * 新增评测条目的请求体。
 *
 * @param question       用户问题（必填）
 * @param expectedDocIds 期望命中的 doc_id（task_id）列表；检索 ground truth
 * @param expectedAnswer 标准答案（Phase 2 答案质量评测 LLM-as-judge 用，可空）
 * @param category       分类：qa / summarization / adversarial
 * @param itemKey        可选用例标识
 * @param source         来源：builtin（默认）/ feedback
 */
public record EvalItemRequest(
        String question,
        List<String> expectedDocIds,
        String expectedAnswer,
        String category,
        String itemKey,
        String source
) {}
