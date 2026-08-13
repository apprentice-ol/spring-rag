package com.nageoffer.ai.rag.chat.agent;

import com.nageoffer.ai.rag.chat.intent.IntentResult;
import com.nageoffer.ai.rag.chat.retrieval.SearchContext;
import com.nageoffer.ai.rag.config.properties.AgentProperties;

/**
 * Agent 编排的统一入参（pipeline / eval 两处入口共用）。
 *
 * @param question      用户原始问题（最终回答用，改写前）
 * @param historyContext 最近对话历史（grade/decompose/rewrite 消解指代用），可空
 * @param intent        意图分类结果（CRAG/Plan-Exec 会读 needsWebSearch 等），eval 场景可 null
 * @param searchContext 检索上下文（已含 rewrittenQuery/topK/threshold/budget/metadata，复用现有类型）
 * @param options       agent 配置（请求级覆盖后的 AgentProperties，非全局单例）
 * @param traceId       当前 OTel traceId（eval 落库 + 前端跳 OpenObserve 用），pipeline 模式可 null
 */
public record AgentRequest(
        String question,
        String historyContext,
        IntentResult intent,
        SearchContext searchContext,
        AgentProperties options,
        String traceId
) {
    /** eval 入口便捷构造（无历史、无意图）。 */
    public static AgentRequest forEval(String question, SearchContext ctx, AgentProperties opts, String traceId) {
        return new AgentRequest(question, "", null, ctx, opts, traceId);
    }
}
