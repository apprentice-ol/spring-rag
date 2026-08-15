package com.nageoffer.ai.rag.chat.agent.impl;

import com.nageoffer.ai.obs.observation.annotation.ObservedStep;
import com.nageoffer.ai.rag.chat.agent.AgentRequest;
import com.nageoffer.ai.rag.chat.agent.AgentRetrievalResult;
import com.nageoffer.ai.rag.chat.agent.AgentTrace;
import com.nageoffer.ai.rag.chat.agent.RagAgent;
import com.nageoffer.ai.rag.chat.agent.RetrievalVerdict;
import com.nageoffer.ai.rag.chat.agent.toolkit.AgentToolkit;
import com.nageoffer.ai.rag.chat.retrieval.MultiChannelRetrievalEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Naive RAG agent（baseline 范式）。
 * <p>单次检索 → 透传 finalChunks，不做评估/重试/改写。
 * <b>行为等价改造前的线性流水线</b>（rewrite 已在 pipeline 上游完成，searchContext.rewrittenQuery 即改写结果），
 * 保证可插拔改造不引入回归（eval 黄金集 Recall@k 应零差异）。
 *
 * <p>关键：不在 agent 内重复 rerank —— rerank 已在 MultiChannelRetrievalEngine 的后处理链跑过一次，
 * agent 内再跑会双重精排。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NaiveRagAgent implements RagAgent {

    private final AgentToolkit toolkit;

    @Override
    public String getType() {
        return "naive";
    }

    @Override
    @ObservedStep("rag.agent.plan")
    public AgentRetrievalResult planAndRetrieve(AgentRequest req) {
        AgentTrace trace = new AgentTrace("naive");
        long t = System.currentTimeMillis();

        // 单次检索（searchContext 已含 rewrittenQuery / 预算 / 意图 metadata）
        MultiChannelRetrievalEngine.RetrievalResult rr = toolkit.retrieve(req.searchContext());
        trace.step("retrieve", "单次检索",
                req.searchContext().getRewrittenQuery(),
                rr.getChannelResults().size() + " 通道, " + rr.getFinalChunks().size() + " 条", t);

        RetrievalVerdict verdict = rr.isEmpty() ? RetrievalVerdict.EMPTY : RetrievalVerdict.READY;
        return new AgentRetrievalResult(rr.getFinalChunks(), rr, trace, verdict);
    }
}
