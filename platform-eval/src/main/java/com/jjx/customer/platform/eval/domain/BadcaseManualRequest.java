package com.jjx.customer.platform.eval.domain;

/**
 * bad case 手工录入请求（管理页；人工发现的失败案例，无线上现场）。
 *
 * @param question 问题（必填）
 * @param note     备注（来源说明 / 期望）
 * @param domain   知识领域（可空，归因确认时补）
 */
public record BadcaseManualRequest(String question, String note, String domain) {
}
