package com.jjx.customer.platform.business.ops.rest;

/**
 * 日志诊断结果（2026-09-12 合流后：agent 出参的 REST 投影）。
 *
 * @param traceId    诊断的链路 ID
 * @param conclusion 诊断结论（Markdown）；缺槽位时为追问说明（outcome=CLARIFY）
 * @param outcome    Agent Outcome 类型：ANSWER_DIRECT（结论）/ CLARIFY（缺信息）/ ESCALATE（升级）
 * @param llmCalls   本次诊断的 LLM 调用次数（成本观测）
 */
public record DiagnoseResponse(
        String traceId,
        String conclusion,
        String outcome,
        int llmCalls
) {
}
