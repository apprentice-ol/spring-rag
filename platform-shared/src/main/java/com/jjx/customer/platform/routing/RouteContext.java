package com.jjx.customer.platform.routing;



import com.jjx.customer.platform.intent.IntentResult;

/**
 * 路由求值上下文（编排层在两个时机求值规则表）：
 * 意图分类前（{@code intent=null}，正则短路类规则可命中——省一次 LLM 往返）与
 * 意图分类后（意图域规则可命中）。规则自行判断输入可用性。
 *
 * @param question           用户原始问题
 * @param ruleNormalized     规则归一化后的问题
 * @param intent             意图分类结果（第一次求值时为 null）
 * @param extractedTraceId   正则提取的 traceId（无则 null）
 * @param activeSessionAgentType 活动追问会话的 agentType（恢复分支已处理，此处恒 null 语义保留）
 * @param agentChoice        用户显式选择的范式（新前端主动选择才发送；可空）
 */
public record RouteContext(String question,
                           String ruleNormalized,
                           IntentResult intent,
                           String extractedTraceId,
                           String activeSessionAgentType,
                           String agentChoice) {
}
