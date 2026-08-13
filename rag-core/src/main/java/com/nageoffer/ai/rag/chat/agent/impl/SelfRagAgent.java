package com.nageoffer.ai.rag.chat.agent.impl;

import com.nageoffer.ai.rag.chat.agent.AgentRequest;
import com.nageoffer.ai.rag.chat.agent.AgentRetrievalResult;
import com.nageoffer.ai.rag.chat.agent.AgentTrace;
import com.nageoffer.ai.rag.chat.agent.ChunkGrade;
import com.nageoffer.ai.rag.chat.agent.GradingResult;
import com.nageoffer.ai.rag.chat.agent.RagAgent;
import com.nageoffer.ai.rag.chat.agent.RetrievalVerdict;
import com.nageoffer.ai.rag.chat.agent.toolkit.AgentToolkit;
import com.nageoffer.ai.rag.chat.retrieval.MultiChannelRetrievalEngine;
import com.nageoffer.ai.rag.chat.retrieval.RetrievedChunk;
import com.nageoffer.ai.rag.chat.retrieval.SearchContext;
import com.nageoffer.ai.rag.common.util.JsonResponseParser;
import com.nageoffer.ai.rag.config.prompt.PromptStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Self-RAG agent（密集反思范式）。
 * <p>retrieve → grade（逐块）→ <b>reflect</b>（LLM 判断"当前片段能否完整回答问题"）→
 * 不足则 rewrite 换表述补检索 → 再 grade + reflect，循环至上限或充分。
 *
 * <p>与 CRAG 的差异：CRAG 只在 grade 后路由一次；Self-RAG 多了 <b>答案充分性反思</b>（reflect），
 * 不仅看"检索结果相不相关"，还看"够不够回答"——这是更密集的反思（对齐 Self-RAG 论文的 reflection token 思想）。
 *
 * <p>学习要点：反思粒度从"检索相关性"（CRAG）升级到"答案充分性"（Self-RAG）。
 * reflect 用 self-rag-reflect prompt + ingestionChatClient + JsonResponseParser。
 */
@Slf4j
@Component
public class SelfRagAgent implements RagAgent {

    private final AgentToolkit toolkit;
    private final ChatClient chatClient;
    private final PromptStore promptStore;

    public SelfRagAgent(AgentToolkit toolkit,
                        @Qualifier("ingestionChatClient") ChatClient chatClient,
                        PromptStore promptStore) {
        this.toolkit = toolkit;
        this.chatClient = chatClient;
        this.promptStore = promptStore;
    }

    @Override
    public String getType() {
        return "self_rag";
    }

    @Override
    public AgentRetrievalResult planAndRetrieve(AgentRequest req) {
        AgentTrace trace = new AgentTrace("self_rag");
        SearchContext ctx = req.searchContext();
        int maxRetries = Math.max(1, req.options().getMaxRetries());

        // 1. 首轮检索 + grade
        long t = System.currentTimeMillis();
        MultiChannelRetrievalEngine.RetrievalResult rr = toolkit.retrieve(ctx);
        MultiChannelRetrievalEngine.RetrievalResult lastRR = rr;
        trace.step("retrieve", "首轮检索", ctx.getRewrittenQuery(),
                rr.getFinalChunks().size() + " 条", t);

        List<RetrievedChunk> accumulated = new ArrayList<>();
        if (!rr.isEmpty()) {
            long tg = System.currentTimeMillis();
            GradingResult gr = toolkit.grade(req.question(), rr.getFinalChunks(), req.options());
            trace.incrementLlmCall();
            trace.step("grade", "逐块评估相关性", null,
                    gr.verdict() + " relevant=" + gr.relevantCount(), tg);
            accumulated.addAll(filterRelevant(rr.getFinalChunks(), gr));
        }

        // 2. 反思循环：reflect(充分?) → 不足则 rewrite 补检索 → 再 reflect
        boolean sufficient = false;
        int retry = 0;
        while (true) {
            t = System.currentTimeMillis();
            sufficient = reflect(req.question(), accumulated, trace, t);
            trace.incrementLlmCall();
            if (sufficient || retry >= maxRetries) {
                break;
            }
            retry++;
            t = System.currentTimeMillis();
            String rewritten = toolkit.rewrite(ctx.getRewrittenQuery(), req.historyContext());
            trace.incrementLlmCall();
            trace.step("rewrite", "反思判定不足，换表述补检索 #" + retry,
                    ctx.getRewrittenQuery(), rewritten, t);
            long tr = System.currentTimeMillis();
            MultiChannelRetrievalEngine.RetrievalResult rr2 = toolkit.retrieve(withQuery(ctx, rewritten));
            trace.step("retrieve", "补检索 #" + retry, rewritten,
                    rr2.getFinalChunks().size() + " 条", tr);
            lastRR = rr2;
            if (!rr2.isEmpty()) {
                long tg2 = System.currentTimeMillis();
                GradingResult gr2 = toolkit.grade(req.question(), rr2.getFinalChunks(), req.options());
                trace.incrementLlmCall();
                trace.step("grade", "补检索评估 #" + retry, null,
                        gr2.verdict() + " relevant=" + gr2.relevantCount(), tg2);
                accumulated.addAll(filterRelevant(rr2.getFinalChunks(), gr2));
            }
        }

        accumulated = dedup(accumulated);
        if (accumulated.isEmpty()) {
            return AgentRetrievalResult.empty(trace, RetrievalVerdict.EMPTY);
        }
        RetrievalVerdict verdict = sufficient ? RetrievalVerdict.READY : RetrievalVerdict.DEGRADED;
        trace.step("finish", sufficient ? "反思充分，回答" : "反思仍不足，降级回答",
                null, accumulated.size() + " 条", System.currentTimeMillis());
        return new AgentRetrievalResult(accumulated, lastRR, trace, verdict);
    }

    /** 反思：LLM 判断 chunks 是否足以完整回答问题。 */
    private boolean reflect(String question, List<RetrievedChunk> chunks, AgentTrace trace, long t) {
        if (chunks == null || chunks.isEmpty()) {
            trace.step("reflect", "无片段，判定不足", null, "insufficient", t);
            return false;
        }
        try {
            String system = promptStore.raw("agent/self-rag-reflect");
            String user = buildReflectUser(question, chunks);
            String response = chatClient.prompt().system(system).user(user).call().content();
            Map<String, Object> m = JsonResponseParser.parseObject(response);
            boolean suff = asBool(m.get("sufficient"), true);
            trace.step("reflect", "反思答案充分性", null,
                    suff ? "sufficient" : "insufficient", t);
            return suff;
        } catch (Exception e) {
            log.warn("[Self-RAG] reflect 异常，默认充分: {}", e.getMessage());
            trace.step("reflect", "反思异常，默认充分", null, "sufficient(fallback)", t);
            return true;
        }
    }

    private String buildReflectUser(String question, List<RetrievedChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户问题：").append(question).append("\n\n可用文档片段：\n");
        for (int i = 0; i < chunks.size(); i++) {
            sb.append('[').append(i + 1).append("] ")
                    .append(preview(chunks.get(i).getContent())).append('\n');
        }
        return sb.toString();
    }

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

    private static String preview(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String s = content.replaceAll("\\s+", " ").trim();
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    private static boolean asBool(Object o, boolean fallback) {
        if (o instanceof Boolean b) {
            return b;
        }
        return o == null ? fallback : Boolean.parseBoolean(o.toString());
    }
}
