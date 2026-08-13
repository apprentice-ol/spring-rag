package com.nageoffer.ai.rag.eval.metrics;

import java.util.List;

/**
 * 检索质量指标打分器（二值相关性：命中=1，未命中=0）。
 *
 * <p>输入「实际召回的 doc_id 列表」与「期望命中的 doc_id 列表」，输出 0~1 分数。
 * {@code retrievedDocIds} 应已做<b>文档级去重保序</b>（同一文档多个 chunk 只算一次，
 * 按首次出现排序）——评的是「文档召没召回」，不是 chunk 数。</p>
 */
public interface MetricScorer {

    /** 指标名（如 recall_at_5 / precision_at_5 / mrr / ndcg_at_10），作为 sa_eval_metric.metric_name 落库 */
    String name();

    /**
     * @param retrievedDocIds 实际召回（文档级去重保序，按相关度降序）
     * @param expectedDocIds  期望命中（ground truth，黄金集 expected_doc_ids）
     * @return 0~1 分数
     */
    double score(List<String> retrievedDocIds, List<String> expectedDocIds);
}
