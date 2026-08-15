package com.nageoffer.ai.rag.chat.agent.impl;

import com.nageoffer.ai.obs.observation.annotation.ObservedStep;
import com.nageoffer.ai.rag.chat.agent.AgentRequest;
import com.nageoffer.ai.rag.chat.agent.AgentRetrievalResult;
import com.nageoffer.ai.rag.chat.agent.AgentTrace;
import com.nageoffer.ai.rag.chat.agent.AgentStepDetails;
import com.nageoffer.ai.rag.chat.agent.ChunkGrade;
import com.nageoffer.ai.rag.chat.agent.GradingResult;
import com.nageoffer.ai.rag.chat.agent.RagAgent;
import com.nageoffer.ai.rag.chat.agent.RetrievalVerdict;
import com.nageoffer.ai.rag.chat.agent.RetrievalWorkspace;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ReAct agent（prompt-driven 版，Thought → Action → Observation 循环）。
 * <p>这是 ReAct 论文的原始范式：LLM 每步输出 JSON 决策 {@code {thought, action, action_input}}，
 * agent 解析 action 并执行对应工具（retrieve/grade/rerank/finish），把 Observation 累积进
 * scratchpad 喂给下一轮，循环直到 {@code finish} 或达到 {@code reactMaxSteps}。
 *
 * <p>用项目已验证的 {@code ingestionChatClient + JsonResponseParser}（与 IntentClassifier/QueryRewriter 同范式），
 * 不依赖 Spring AI function calling 的手动循环 API（ToolContext 传递/executeToolCalls 循环控制签名未完全确认，
 * 作为可选升级后续再加原生 tool calling 版做对照）。
 *
 * <p><b>学习要点</b>：与 CRAG（代码固定调度 grade→route）不同，ReAct 的"调哪个工具、何时停"完全由 LLM
 * 自主决策 —— 模型看 Observation 决定下一步。对比 Naive/CRAG 看"决策权"从代码移到 LLM 的差异。
 *
 * <p>终止保证（无死循环）：① 模型输出 finish；② 模型未给 action；③ {@code step >= reactMaxSteps} 强制收尾。
 */
@Slf4j
@Component
public class ReActRagAgent implements RagAgent {

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private final AgentToolkit toolkit;
    private final ChatClient chatClient;
    private final PromptStore promptStore;

    public ReActRagAgent(AgentToolkit toolkit,
                         @Qualifier("ingestionChatClient") ChatClient chatClient,
                         PromptStore promptStore) {
        this.toolkit = toolkit;
        this.chatClient = chatClient;
        this.promptStore = promptStore;
    }

    @Override
    public String getType() {
        return "react";
    }

    @Override
    @ObservedStep("rag.agent.plan")
    public AgentRetrievalResult planAndRetrieve(AgentRequest req) {
        AgentTrace trace = new AgentTrace("react");
        RetrievalWorkspace ws = new RetrievalWorkspace();
        String system = promptStore.raw("agent/react-system");
        String question = req.question();
        int maxSteps = Math.max(1, req.options().getReactMaxSteps());
        StringBuilder scratchpad = new StringBuilder();

        for (int step = 0; step < maxSteps; step++) {
            long t = System.currentTimeMillis();
            String userMsg = buildUserMsg(question, scratchpad);
            String response;
            try {
                response = chatClient.prompt().system(system).user(userMsg).call().content();
            } catch (Exception e) {
                log.warn("[ReAct] LLM 决策调用异常: {}", e.getMessage());
                trace.step("error", "LLM 异常: " + e.getMessage(), null, null, t);
                break;
            }
            trace.incrementLlmCall();

            Map<String, Object> decision = JsonResponseParser.parseObject(response);
            String thought = asStr(decision.get("thought"), "");
            String action = asStr(decision.get("action"), "").toLowerCase().trim();
            String actionInput = asStr(decision.get("action_input"), "");

            if (action.isEmpty()) {
                trace.step("think", thought + "（未给 action，结束）", response, "停止", t);
                break;
            }
            if ("finish".equals(action)) {
                trace.step("think", thought, null, "模型 thought", t);
                trace.step("finish", "模型决定完成检索", actionInput,
                        ws.snapshot().size() + " 条", System.currentTimeMillis());
                break;
            }
            String observation = executeAction(action, actionInput, ws, req, trace, t, thought);
            scratchpad.append(observation);
        }

        if (ws.snapshot().isEmpty()) {
            return AgentRetrievalResult.empty(trace, RetrievalVerdict.EMPTY);
        }
        return new AgentRetrievalResult(ws.snapshot(), ws.getLastRetrieval(), trace, RetrievalVerdict.READY);
    }

    private String buildUserMsg(String question, StringBuilder scratchpad) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户问题：").append(question).append("\n\n");
        if (scratchpad.length() == 0) {
            sb.append("现在开始第一步，输出 JSON 决策。\n");
        } else {
            sb.append("已执行步骤：\n").append(scratchpad)
                    .append("\n请基于以上 Observation 决定下一步（输出 JSON）。\n");
        }
        return sb.toString();
    }

    private String executeAction(String action, String input, RetrievalWorkspace ws,
                                 AgentRequest req, AgentTrace trace, long stepStart, String thought) {
        switch (action) {
            case "retrieve":
                return doRetrieve(input, ws, req, trace, stepStart, thought);
            case "grade":
                return doGrade(input, ws, req, trace, stepStart, thought);
            case "rerank":
                return doRerank(input, ws, req, trace, stepStart, thought);
            default:
                trace.step("think", thought + " | 未知 action=" + action, input, "忽略", stepStart);
                return "▶ Action: " + action + "（未知，可选 retrieve/grade/rerank/finish）\n\n";
        }
    }

    private String doRetrieve(String input, RetrievalWorkspace ws, AgentRequest req,
                              AgentTrace trace, long t, String thought) {
        String q = StringUtils.hasText(input) ? input.trim() : req.searchContext().getRewrittenQuery();
        SearchContext ctx = withQuery(req.searchContext(), q);
        MultiChannelRetrievalEngine.RetrievalResult rr = toolkit.retrieve(ctx);
        ws.setLastRetrieval(rr);
        StringBuilder obs = new StringBuilder();
        List<AgentStepDetails.ChunkHit> hits = new ArrayList<>();
        for (RetrievedChunk c : rr.getFinalChunks()) {
            int ref = ws.register(c);
            ws.select(c);
            String prev = preview(c.getContent());
            obs.append("[ref=").append(ref).append("] ").append(prev).append('\n');
            hits.add(new AgentStepDetails.ChunkHit(
                    ref, c.getScore(), c.getOriginalScore(), docName(c), channel(c), prev));
        }
        trace.stepDetail("retrieve", thought, q, rr.getFinalChunks().size() + " 条", t,
                new AgentStepDetails.Retrieve(q, hits));
        return "▶ Action: retrieve(\"" + q + "\") → 命中 " + rr.getFinalChunks().size() + " 条\n" + obs + "\n";
    }

    private String doGrade(String input, RetrievalWorkspace ws, AgentRequest req,
                           AgentTrace trace, long t, String thought) {
        List<Integer> refs = parseIntList(input);
        List<RetrievedChunk> targets = new ArrayList<>();
        for (int r : refs) {
            RetrievedChunk c = ws.get(r);
            if (c != null) {
                targets.add(c);
            }
        }
        if (targets.isEmpty()) {
            trace.step("grade", thought, input, "无有效 ref", t);
            return "▶ Action: grade(" + input + ") → 无有效 ref\n\n";
        }
        GradingResult gr = toolkit.grade(req.question(), targets, req.options());
        trace.incrementLlmCall();
        List<AgentStepDetails.GradeRow> rows = new ArrayList<>();
        for (ChunkGrade g : gr.grades()) {
            rows.add(new AgentStepDetails.GradeRow(g.chunkRef(), g.score(), g.relevant(), g.reason()));
        }
        trace.stepDetail("grade", thought, input,
                gr.verdict() + " relevant=" + gr.relevantCount(), t,
                new AgentStepDetails.Grade(gr.verdict().name(), gr.relevantCount(),
                        targets.size(), gr.avgScore(), rows));
        return "▶ Action: grade(" + input + ") → " + gr.verdict()
                + ", 相关 " + gr.relevantCount() + "/" + targets.size() + "\n\n";
    }

    private String doRerank(String input, RetrievalWorkspace ws, AgentRequest req,
                            AgentTrace trace, long t, String thought) {
        int topN = parseInt(input, req.searchContext().getTopK());
        List<RetrievedChunk> snapshot = ws.snapshot();
        if (snapshot.isEmpty()) {
            trace.step("rerank", thought, input, "无候选", t);
            return "▶ Action: rerank(" + input + ") → 无候选片段\n\n";
        }
        // before：snapshot 是已注册的原对象，identity 匹配 refOf 可用；
        // 同时建 content→ref 反查：rerank 返回新构造的 RetrievedChunk（见 BaiLianRerankClient），
        // identity 失配，按内容兜底把重排后的片段映射回原 ref。
        List<Integer> before = new ArrayList<>();
        Map<String, Integer> contentToRef = new HashMap<>();
        for (RetrievedChunk c : snapshot) {
            int ref = ws.refOf(c);
            before.add(ref);
            contentToRef.putIfAbsent(contentKey(c), ref);
        }
        List<RetrievedChunk> ranked = toolkit.rerank(req.question(), snapshot, topN);
        ws.clearSelected();
        List<AgentStepDetails.RerankRow> after = new ArrayList<>();
        for (RetrievedChunk c : ranked) {
            ws.select(c);
            after.add(new AgentStepDetails.RerankRow(
                    contentToRef.getOrDefault(contentKey(c), -1), c.getScore()));
        }
        trace.stepDetail("rerank", thought, input, "取前 " + ranked.size() + " 条", t,
                new AgentStepDetails.Rerank(topN, before, after));
        return "▶ Action: rerank(" + topN + ") → 精排取前 " + ranked.size() + " 条\n\n";
    }

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

    private static String preview(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String s = content.replaceAll("\\s+", " ").trim();
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    /** chunk 来源文档名（与 MultiChannelRetrievalEngine 一致用 doc_name 字段）。 */
    private static String docName(RetrievedChunk c) {
        if (c.getMetadata() == null) {
            return "?";
        }
        Object v = c.getMetadata().get("doc_name");
        return v != null ? String.valueOf(v) : "?";
    }

    /** chunk 来源检索通道名（VECTOR/KEYWORD/...）。 */
    private static String channel(RetrievedChunk c) {
        return c.getChannelType() != null ? c.getChannelType().name() : "?";
    }

    /** 用内容做 chunk→ref 反查键（rerank 返回新对象，identity 失配时兜底）。 */
    private static String contentKey(RetrievedChunk c) {
        return c.getContent() == null ? "" : c.getContent();
    }

    private static List<Integer> parseIntList(String s) {
        List<Integer> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        Matcher m = DIGITS.matcher(s);
        while (m.find()) {
            out.add(Integer.parseInt(m.group()));
        }
        return out;
    }

    private static int parseInt(String s, int fallback) {
        if (s == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String asStr(Object o, String fallback) {
        return o == null ? fallback : o.toString();
    }
}
