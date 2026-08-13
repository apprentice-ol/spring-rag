package com.nageoffer.ai.rag.eval.domain;

import java.util.List;

/**
 * 触发评测运行的请求参数。
 *
 * @param datasetId      要跑的数据集 ID
 * @param paramsOverride 检索参数覆盖；为 null 时用 ChatProperties 当前值
 * @param category       按分类筛选（qa/summarization/adversarial 等）；为 null/空 则不限
 * @param limit          抽样数量上限；为 null/≤0 则跑全量匹配条目
 * @param rewriteEnabled 是否启用查询改写（QueryRewriter）；null/false=裸检索（默认，向后兼容）
 * @param paradigm       agent 范式（naive/crag/self_rag/react/plan_execute）；null/空 用 naive
 * @param itemIds        指定只跑这些条目 id（精确子集）；非空时覆盖 category/limit，不再抽样。
 *                       用于 agent 对照场景：多个范式传同一组 itemIds，保证跑相同问题、对照公平。
 */
public record EvalRunOptions(
        Long datasetId,
        EvalParamSnapshot paramsOverride,
        String category,
        Integer limit,
        Boolean rewriteEnabled,
        String paradigm,
        List<Long> itemIds
) {}
