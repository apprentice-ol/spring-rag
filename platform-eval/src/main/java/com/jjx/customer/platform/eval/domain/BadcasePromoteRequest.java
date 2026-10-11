package com.jjx.customer.platform.eval.domain;

import java.util.List;

/**
 * bad case 转正请求：补 ground truth 后搬到目标领域数据集。
 *
 * <p>转正 = UPDATE dataset_id（从归集池搬家）+ badcase_status=READY + enabled=1，
 * 下轮跑批自动被 EvalRunner 抽中。</p>
 *
 * @param targetDatasetId 目标领域数据集 id（不能是归集池）
 * @param expectedDocIds  检索 ground truth（必填，与 chunk.metadata.doc_id 同源口径）
 * @param expectedAnswer  标准答案（可选；建议用起草辅助 + 人工修订）
 * @param domain          知识领域（可在此步补/修）
 */
public record BadcasePromoteRequest(
        Long targetDatasetId,
        List<String> expectedDocIds,
        String expectedAnswer,
        String domain
) {
}
