package com.jjx.customer.platform.eval.framework;

import com.jjx.customer.platform.eval.domain.EvalParamSnapshot;

/**
 * 一次评测 run 的上下文（sink 感知 run 级信息的载体）。
 *
 * @param runId     sa_eval_run.id
 * @param datasetId sa_eval_dataset.id
 * @param datasetName 数据集名（Langfuse dataset 同名同步的依据）
 * @param paradigm  生效 agent 范式
 * @param params    参数快照
 */
public record EvalRunContext(Long runId, Long datasetId, String datasetName,
                             String paradigm, EvalParamSnapshot params) {
}
