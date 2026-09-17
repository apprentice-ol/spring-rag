package com.jjx.customer.platform.eval.framework;

/**
 * 单个评测指标得分（评测维度的最小产出单位）。
 *
 * @param name   指标名（落库 metric_name / Langfuse score name，如 recall_at_5、context_recall）
 * @param value  分数 0~1（DB 约定 -1 = 失败行，由 sink 自行处理，scorer 不产出负值）
 * @param comment 评分明细（Langfuse score 的 comment 展示用；DB sink 可忽略——落库口径不变）
 */
public record EvalScore(String name, double value, String comment) {

    public static EvalScore of(String name, double value) {
        return new EvalScore(name, value, null);
    }
}
