package com.nageoffer.ai.rag.chat.agent.impl;

import com.nageoffer.ai.rag.chat.agent.AgentRequest;
import com.nageoffer.ai.rag.chat.agent.AgentRetrievalResult;
import com.nageoffer.ai.rag.chat.agent.AgentTrace;
import com.nageoffer.ai.rag.chat.agent.ChunkGrade;
import com.nageoffer.ai.rag.chat.agent.GradeVerdict;
import com.nageoffer.ai.rag.chat.agent.GradingResult;
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
 * Corrective RAG agent（CRAG 范式）。
 * <p>retrieve → grade（LLM 评估相关性）→ 三档路由：
 * <ul>
 *   <li>{@code ALL_RELEVANT}：全部相关，直接用于回答</li>
 *   <li>{@code PARTIAL}：部分相关，取 relevant 子集回答</li>
 *   <li>{@code ALL_IRRELEVANT} / 首轮空：触发 <b>corrective 重试</b> —— decompose 拆子查询，
 *       逐个 retrieve + grade，合并所有 relevant chunk</li>
 * </ul>
 * 重试仍无 relevant：{@code enableWebFallback=true} 时走 web 兜底（DEGRADED）；
 * 否则有首次结果则降级回答（DEGRADED，比 EMPTY 友好），无则 EMPTY。
 *
 * <p>学习要点：这是 <b>corrective</b> 范式 —— 检索后用 LLM 自评、不达标自动换 query 重试，
 * 决策由代码固定调度（LLM 只做局部 grade 判断）。对比 Naive（单次）与 ReAct（LLM 自主调度）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CragRagAgent implements RagAgent {

    private final AgentToolkit toolkit;

    @Override
    public String getType() {
        return "crag";
    }

    @Override
    public AgentRetrievalResult planAndRetrieve(AgentRequest req) {
        AgentTrace trace = new AgentTrace("crag");
        SearchContext ctx = req.searchContext();

        // 1) 首轮检索
        long t = System.currentTimeMillis();
        MultiChannelRetrievalEngine.RetrievalResult rr = toolkit.retrieve(ctx);
        trace.step("retrieve", "首轮检索", ctx.getRewrittenQuery(),
                rr.getChannelResults().size() + " 通道, " + rr.getFinalChunks().size() + " 条", t);

        if (rr.isEmpty()) {
            // 首轮就空 → 跳过 grade，直接 corrective 重试
            return correctiveRetry(req, trace, rr, List.of());
        }

        // 2) grade（LLM 评估每个 chunk 相关性）
        t = System.currentTimeMillis();
        GradingResult gr = toolkit.grade(req.question(), rr.getFinalChunks(), req.options());
        trace.incrementLlmCall();
        trace.step("grade", "评估相关性", rr.getFinalChunks().size() + " 条",
                gr.verdict() + " relevant=" + gr.relevantCount(), t);

        // 3) 三档路由
        switch (gr.verdict()) {
            case ALL_RELEVANT:
                trace.step("route", "全部相关，直接回答", null,
                        rr.getFinalChunks().size() + " 条", System.currentTimeMillis());
                return new AgentRetrievalResult(rr.getFinalChunks(), rr, trace, RetrievalVerdict.READY);
            case PARTIAL:
                List<RetrievedChunk> relevant = filterRelevant(rr.getFinalChunks(), gr);
                trace.step("route", "部分相关，取 relevant 子集", null,
                        relevant.size() + " 条", System.currentTimeMillis());
                return new AgentRetrievalResult(relevant, rr, trace, RetrievalVerdict.READY);
            case ALL_IRRELEVANT:
            default:
                return correctiveRetry(req, trace, rr, filterRelevant(rr.getFinalChunks(), gr));
        }
    }

    /**
     * Corrective 重试：拆子查询逐个 retrieve + grade，合并所有 relevant。
     * 重试上限由 {@code maxRetries} 控制（限制子查询数，防失控）。
     */
    private AgentRetrievalResult correctiveRetry(AgentRequest req, AgentTrace trace,
                                                 MultiChannelRetrievalEngine.RetrievalResult firstRR,
                                                 List<RetrievedChunk> firstRelevant) {
        SearchContext ctx = req.searchContext();
        int maxRetries = Math.max(1, req.options().getMaxRetries());

        long t = System.currentTimeMillis();
        List<String> subQueries = toolkit.decompose(ctx.getRewrittenQuery());
        trace.incrementLlmCall();
        trace.step("decompose", "拆子查询重试", ctx.getRewrittenQuery(),
                subQueries.size() + " 个子查询（上限 " + maxRetries + "）", t);

        List<RetrievedChunk> merged = new ArrayList<>(firstRelevant);
        MultiChannelRetrievalEngine.RetrievalResult lastRR = firstRR;
        int tried = 0;
        for (String sub : subQueries) {
            if (tried >= maxRetries) {
                break;
            }
            tried++;
            t = System.currentTimeMillis();
            MultiChannelRetrievalEngine.RetrievalResult rr = toolkit.retrieve(withQuery(ctx, sub));
            trace.step("retrieve", "重试#" + tried, sub, rr.getFinalChunks().size() + " 条", t);
            lastRR = rr;
            if (rr.isEmpty()) {
                continue;
            }

            t = System.currentTimeMillis();
            GradingResult gr = toolkit.grade(req.question(), rr.getFinalChunks(), req.options());
            trace.incrementLlmCall();
            trace.step("grade", "重试#" + tried + " 评估", null,
                    gr.verdict() + " relevant=" + gr.relevantCount(), t);
            if (gr.verdict() != GradeVerdict.ALL_IRRELEVANT) {
                merged.addAll(filterRelevant(rr.getFinalChunks(), gr));
            }
        }

        if (!merged.isEmpty()) {
            List<RetrievedChunk> deduped = dedup(merged);
            trace.step("finish", "重试命中，合并去重", null, deduped.size() + " 条", System.currentTimeMillis());
            return new AgentRetrievalResult(deduped, lastRR, trace, RetrievalVerdict.READY);
        }

        // 重试仍无 relevant → web 兜底（可选）
        if (req.options().isEnableWebFallback()) {
            t = System.currentTimeMillis();
            List<RetrievedChunk> web = toolkit.webSearch(ctx);
            if (!web.isEmpty()) {
                trace.step("web_search", "web 兜底命中", null, web.size() + " 条", t);
                return new AgentRetrievalResult(web, lastRR, trace, RetrievalVerdict.DEGRADED);
            }
            trace.step("web_search", "web 兜底无结果", null, "0 条", t);
        }

        // 无 web / web 空：有首次结果则降级回答（比 EMPTY 友好），否则 EMPTY
        if (!firstRR.isEmpty()) {
            trace.step("finish", "重试失败，用首次结果降级回答", null,
                    firstRR.getFinalChunks().size() + " 条", System.currentTimeMillis());
            return new AgentRetrievalResult(firstRR.getFinalChunks(), firstRR, trace, RetrievalVerdict.DEGRADED);
        }
        return AgentRetrievalResult.empty(trace, RetrievalVerdict.EMPTY);
    }

    /** 按 grade 取 relevant chunk（chunkRef 是 1-based 下标，对齐 grade 输入编号）。 */
    private List<RetrievedChunk> filterRelevant(List<RetrievedChunk> chunks, GradingResult gr) {
        List<RetrievedChunk> out = new ArrayList<>();
        for (ChunkGrade g : gr.grades()) {
            if (g.relevant()) {
                int idx = g.chunkRef() - 1;
                if (idx >= 0 && idx < chunks.size()) {
                    out.add(chunks.get(idx));
                }
            }
        }
        return out;
    }

    /** 复制 SearchContext，替换 rewrittenQuery（重试换 query 用）。 */
    private SearchContext withQuery(SearchContext orig, String rewritten) {
        return SearchContext.builder()
                .query(orig.getQuery())
                .rewrittenQuery(rewritten)
                .topK(orig.getTopK())
                .threshold(orig.getThreshold())
                .budget(orig.getBudget())
                .metadata(orig.getMetadata())
                .build();
    }

    /** 按 content 去重，保持首次出现顺序。 */
    private List<RetrievedChunk> dedup(List<RetrievedChunk> chunks) {
        Map<String, RetrievedChunk> seen = new LinkedHashMap<>();
        for (RetrievedChunk c : chunks) {
            String key = c.getContent() == null
                    ? "@" + System.identityHashCode(c)
                    : c.getContent();
            seen.putIfAbsent(key, c);
        }
        return new ArrayList<>(seen.values());
    }
}
