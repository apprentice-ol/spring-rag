package com.jjx.customer.platform.eval.domain;

import java.time.LocalDateTime;

/**
 * bad case 视图（管理页列表/详情/归集返回值）。
 *
 * <p>底层即 {@code sa_eval_item} 行（badcase 是评测条目的一种，统一模型），
 * evidence / triage 从 JSONB 列解析为对象返回，前端零解析。</p>
 */
public record BadcaseView(
        Long id,
        Long datasetId,
        String question,
        String origin,
        String domain,
        String badcaseStatus,
        String feedbackReason,
        String feedbackNote,
        BadcaseEvidence evidence,
        BadcaseTriage triage,
        String expectedAnswer,
        Integer expectedDocCount,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
}
