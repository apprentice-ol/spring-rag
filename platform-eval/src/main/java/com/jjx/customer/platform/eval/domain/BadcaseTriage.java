package com.jjx.customer.platform.eval.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * bad case 归因结论（{@code sa_eval_item.triage}）：规则预判 + 人工确认。
 *
 * @param stage 失败环节（{@link TriageStage} 枚举名）
 * @param auto  是否规则自动预判（false = 人工确认/修改过）
 * @param note  判定说明（规则给出理由 / 人工备注）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BadcaseTriage(String stage, Boolean auto, String note) {
}
