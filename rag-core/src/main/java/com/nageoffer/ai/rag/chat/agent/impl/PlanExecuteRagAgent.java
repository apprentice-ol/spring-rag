package com.nageoffer.ai.rag.chat.agent.impl;

import com.nageoffer.ai.rag.chat.agent.AgentRequest;
import com.nageoffer.ai.rag.chat.agent.AgentRetrievalResult;
import com.nageoffer.ai.rag.chat.agent.AgentTrace;
import com.nageoffer.ai.rag.chat.agent.RagAgent;
import com.nageoffer.ai.rag.chat.agent.RetrievalVerdict;
import com.nageoffer.ai.rag.chat.agent.toolkit.AgentToolkit;
import com.nageoffer.ai.rag.chat.retrieval.MultiChannelRetrievalEngine;
import com.nageoffer.ai.rag.chat.retrieval.RetrievedChunk;
import com.nageoffer.ai.rag.chat.retrieval.SearchContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plan-and-Execute agent（规划-执行分离范式，ReWOO 思路）。
 * <p><b>planner</b> 一次性把问题拆成子查询列表（decompose，不边走边看）→
 * <b>executor</b> 对每个子查询 retrieve → 合并去重 → <b>solver</b> rerank 精排定稿。
 *
 * <p>与 ReAct 的差异：ReAct 是"边走边决策"（每步 LLM 看观察决定下一步）；
 * Plan-Execute 是"先规划全部，再批量执行"——planner 只调一次 LLM，中间执行不再问 LLM，
 * 省 token、延迟更低，代价是规划错了无法中途修正（无反思）。
 *
 * <p>学习要点：决策时序的差异 —— 在线决策（ReAct）vs 离线规划（Plan-Execute）。
 * planner 复用 toolkit.decompose；executor 复用 toolkit.retrieve；solver 复用 toolkit.rerank。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanExecuteRagAgent implements RagAgent {

    private final AgentToolkit toolkit;

    @Override
    public String getType() {
        return "plan_execute";
    }

    @Override
    public AgentRetrievalResult planAndRetrieve(AgentRequest req) {
        AgentTrace trace = new AgentTrace("plan_execute");
        SearchContext ctx = req.searchContext();

        // 1. planner：一次性拆子查询（不边走边看）
        long t = System.currentTimeMillis();
        List<String> subQueries = toolkit.decompose(ctx.getRewrittenQuery());
        trace.incrementLlmCall();
        trace.step("plan", "planner 一次性拆子查询", ctx.getRewrittenQuery(),
                subQueries.size() + " 个: " + subQueries, t);

        // 2. executor：对每个子查询 retrieve（顺序执行，收集全部）
        List<RetrievedChunk> all = new ArrayList<>();
        MultiChannelRetrievalEngine.RetrievalResult lastRR = null;
        for (int i = 0; i < subQueries.size(); i++) {
            String sub = subQueries.get(i);
            t = System.currentTimeMillis();
            MultiChannelRetrievalEngine.RetrievalResult rr = toolkit.retrieve(withQuery(ctx, sub));
            trace.step("execute", "执行子查询#" + (i + 1), sub,
                    rr.getFinalChunks().size() + " 条", t);
            lastRR = rr;
            all.addAll(rr.getFinalChunks());
        }

        // 3. 合并去重
        all = dedup(all);
        trace.step("merge", "合并去重", null, all.size() + " 条", System.currentTimeMillis());

        // 4. solver：rerank 精排定稿
        t = System.currentTimeMillis();
        List<RetrievedChunk> ranked = toolkit.rerank(req.question(), all, req.searchContext().getTopK());
        trace.step("rerank", "精排定稿", null, "取前 " + ranked.size() + " 条", t);

        if (ranked.isEmpty()) {
            return AgentRetrievalResult.empty(trace, RetrievalVerdict.EMPTY);
        }
        return new AgentRetrievalResult(ranked, lastRR, trace, RetrievalVerdict.READY);
    }

    private SearchContext withQuery(SearchContext orig, String rewritten) {
        return SearchContext.builder()
                .query(orig.getQuery()).rewrittenQuery(rewritten)
                .topK(orig.getTopK()).threshold(orig.getThreshold())
                .budget(orig.getBudget()).metadata(orig.getMetadata())
                .build();
    }

    private List<RetrievedChunk> dedup(List<RetrievedChunk> chunks) {
        Map<String, RetrievedChunk> seen = new LinkedHashMap<>();
        for (RetrievedChunk c : chunks) {
            String key = c.getContent() == null ? "@" + System.identityHashCode(c) : c.getContent();
            seen.putIfAbsent(key, c);
        }
        return new ArrayList<>(seen.values());
    }
}
