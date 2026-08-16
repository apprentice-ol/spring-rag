package com.nageoffer.ai.rag.diagnose.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.rag.chat.retrieval.MultiChannelRetrievalEngine;
import com.nageoffer.ai.rag.chat.retrieval.RetrievedChunk;
import com.nageoffer.ai.rag.chat.retrieval.RetrievalBudget;
import com.nageoffer.ai.rag.chat.retrieval.SearchContext;
import com.nageoffer.ai.rag.config.properties.ChatProperties;
import com.jjx.ai.llmobservability.backends.openobserve.OpenObserveQueryClient;
import com.jjx.ai.llmobservability.backends.openobserve.dto.TraceLogEntry;
import com.jjx.ai.llmobservability.backends.openobserve.OpenObserveProperties;
import com.nageoffer.ai.rag.config.prompt.PromptStore;
import com.nageoffer.ai.rag.diagnose.dto.DiagnosePreview;
import com.nageoffer.ai.rag.diagnose.dto.DiagnoseResponse;
import com.nageoffer.ai.rag.diagnose.dto.MatchResult;
import com.nageoffer.ai.rag.diagnose.dto.RelatedDoc;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 日志诊断编排：traceId → 查日志 → 提报错 → 检索知识库 → LLM 给根因+修复建议。
 *
 * <p>三段式（供对话分支做<b>匹配校验</b>，避免对无关 traceId 浪费完整诊断）：
 * <ul>
 *   <li>{@link #preview}：轻量查日志+提报错+errorSummary（无 LLM/检索，~1s）</li>
 *   <li>{@link #checkMatch}：LLM 宽松判断「用户业务问题 vs 报错」是否相关（~2-3s）</li>
 *   <li>{@link #complete}：检索+LLM 建议（重，~10-20s）</li>
 * </ul>
 *
 * <p>复用既有能力：
 * {@link OpenObserveQueryClient} 查日志、{@link MultiChannelRetrievalEngine#retrieve} 检索、
 * {@code ingestionChatClient} 裸 ChatClient 生成诊断/匹配。
 */
@Slf4j
@Service
public class LogDiagnoseService {

    private static final int ERROR_SUMMARY_BUDGET = 2000;
    private static final int STACK_CAP = 800;
    private static final int PREVIEW_CAP = 200;
    private static final int DOCS_CONTEXT_CAP = 3000;

    private final OpenObserveQueryClient logClient;
    private final OpenObserveProperties ooProperties;
    private final MultiChannelRetrievalEngine retrievalEngine;
    private final ChatProperties chatProperties;
    private final PromptStore promptStore;
    private final ChatClient diagnoseChatClient;
    private final ObjectMapper objectMapper;

    public LogDiagnoseService(OpenObserveQueryClient logClient,
                              OpenObserveProperties ooProperties,
                              MultiChannelRetrievalEngine retrievalEngine,
                              ChatProperties chatProperties,
                              PromptStore promptStore,
                              @Qualifier("ingestionChatClient") ChatClient diagnoseChatClient,
                              ObjectMapper objectMapper) {
        this.logClient = logClient;
        this.ooProperties = ooProperties;
        this.retrievalEngine = retrievalEngine;
        this.chatProperties = chatProperties;
        this.promptStore = promptStore;
        this.diagnoseChatClient = diagnoseChatClient;
        this.objectMapper = objectMapper;
    }

    // ===================== 一体流程（API 直调，无对话语境不校验） =====================

    /** 一体诊断：preview + complete。DiagnoseController 走这里（无对话语境，跳过匹配校验）。 */
    public DiagnoseResponse diagnose(String traceId) {
        return complete(preview(traceId));
    }

    // ===================== 三段式（对话分支用） =====================

    /**
     * 轻量预览：查日志 + 提报错 + errorSummary（无 LLM、无检索）。
     * <p>供 {@code StreamChatPipeline} 做 preview → 匹配校验 → 决定是否 complete。
     */
    public DiagnosePreview preview(String traceId) {
        log.info("[诊断][preview] traceId={}", traceId);
        List<TraceLogEntry> descLogs = logClient.searchLogsByTraceId(traceId, ooProperties.getMaxLogs());
        if (descLogs.isEmpty()) {
            return new DiagnosePreview(traceId, List.of(), List.of(), null);
        }
        List<TraceLogEntry> logs = new ArrayList<>(descLogs);
        Collections.reverse(logs);
        List<TraceLogEntry> errors = logs.stream()
                .filter(e -> "ERROR".equalsIgnoreCase(e.level()) || StringUtils.hasText(e.exception()))
                .toList();
        String errorSummary = errors.isEmpty() ? null : buildErrorSummary(errors);
        log.info("[诊断][preview] traceId={} 日志={}条, 报错={}条", traceId, logs.size(), errors.size());
        return new DiagnosePreview(traceId, logs, errors, errorSummary);
    }

    /**
     * 完整诊断：检索知识库 + LLM 建议（基于 preview.errorSummary）。
     * <p>preview 无日志/无报错时直接返回提示响应，不调 LLM。
     */
    public DiagnoseResponse complete(DiagnosePreview p) {
        if (p.logs().isEmpty()) {
            return new DiagnoseResponse(p.traceId(), List.of(), List.of(), List.of(),
                    "未查到该 traceId 的日志。可能原因：日志未上报 / traceId 错误 / 超出 "
                            + ooProperties.getLookbackDays() + " 天查询窗口。");
        }
        if (p.errors().isEmpty()) {
            return new DiagnoseResponse(p.traceId(), p.logs(), List.of(), List.of(),
                    "该 trace 未记录报错日志（无 ERROR 且无异常堆栈）。");
        }
        List<RelatedDoc> docs = retrieveDocs(p.errorSummary());
        log.info("[诊断][complete] traceId={} 报错={}条, 检索命中={}条文档", p.traceId(), p.errors().size(), docs.size());
        String suggestion = generateSuggestion(p.errorSummary(), docs);
        return new DiagnoseResponse(p.traceId(), p.logs(), p.errors(), docs, suggestion);
    }

    /**
     * 匹配校验：用户业务问题 vs traceId 报错 是否相关（LLM 宽松判断）。
     * <p>{@code relevant=false} 表示明显不相关（应反问）；不确定/异常一律放行（relevant=true）。
     */
    public MatchResult checkMatch(String userQuery, DiagnosePreview p) {
        if (p == null || p.errors().isEmpty() || !StringUtils.hasText(userQuery)) {
            return new MatchResult(true, null, null);
        }
        try {
            String systemPrompt = promptStore.raw("diagnose/match-check");
            String userMsg = "用户问题：" + userQuery
                    + "\n\n报错摘要：\n" + p.errorSummary()
                    + "\n\ntrace 日志概览（含接口路径，用于判断这个 trace 属于哪个业务模块）：\n" + buildTraceContext(p.logs());
            String resp = diagnoseChatClient.prompt()
                    .system(systemPrompt)
                    .user(userMsg)
                    .call()
                    .content();
            log.info("[诊断][checkMatch] LLM 原始: {}", resp);
            return parseMatchResult(resp);
        } catch (Exception e) {
            log.warn("[诊断][checkMatch] 调用异常，放行: {}", e.getMessage());
            return new MatchResult(true, null, null);
        }
    }

    private MatchResult parseMatchResult(String resp) {
        try {
            JsonNode node = objectMapper.readTree(extractJson(resp));
            boolean relevant = node.path("relevant").asBoolean(true);
            String brief = node.path("error_brief").asText("");
            String reason = node.path("reason").asText("");
            return new MatchResult(relevant, brief, reason);
        } catch (Exception e) {
            log.warn("[诊断][checkMatch] 解析异常，放行: {}", e.getMessage());
            return new MatchResult(true, null, null);
        }
    }

    /** 剥离可能的 ```json / ``` 包裹，取 JSON 正文 */
    private static String extractJson(String resp) {
        if (resp == null) {
            return "{}";
        }
        String json = resp;
        if (json.contains("```json")) {
            json = json.substring(json.indexOf("```json") + 7, json.lastIndexOf("```"));
        } else if (json.contains("```")) {
            json = json.substring(json.indexOf("```") + 3, json.lastIndexOf("```"));
        }
        return json.trim();
    }

    /** trace 日志概览：前若干条 message（含接口路径等业务线索），供匹配校验判断 trace 所属业务模块 */
    private String buildTraceContext(List<TraceLogEntry> logs) {
        if (logs == null || logs.isEmpty()) {
            return "(无日志)";
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (TraceLogEntry e : logs) {
            if (n++ >= 8) {
                break;
            }
            String msg = e.message() == null ? "" : e.message();
            if (msg.length() > 120) {
                msg = msg.substring(0, 120);
            }
            sb.append("- ").append(msg).append("\n");
        }
        return sb.toString().trim();
    }

    // ===================== 内部：报错摘要 / 检索 / 建议 =====================

    /** 拼接报错摘要：exceptionClass + message + 截断堆栈，多条用 --- 分隔，整体 ≤ ERROR_SUMMARY_BUDGET */
    private String buildErrorSummary(List<TraceLogEntry> errors) {
        StringBuilder sb = new StringBuilder();
        for (TraceLogEntry e : errors) {
            if (sb.length() >= ERROR_SUMMARY_BUDGET) {
                sb.append("\n...(更多报错已截断)");
                break;
            }
            sb.append("[").append(StringUtils.hasText(e.exceptionClass()) ? e.exceptionClass() : e.level()).append("] ");
            sb.append(e.message() == null ? "" : e.message()).append("\n");
            if (StringUtils.hasText(e.exception())) {
                String stack = e.exception();
                if (stack.length() > STACK_CAP) {
                    stack = stack.substring(0, STACK_CAP) + "...(堆栈截断)";
                }
                sb.append(stack).append("\n");
            }
            sb.append("---\n");
        }
        return sb.toString().trim();
    }

    /** 用报错摘要走完整多通道检索（向量+关键词+rerank），命中转 RelatedDoc */
    private List<RelatedDoc> retrieveDocs(String errorSummary) {
        try {
            SearchContext ctx = SearchContext.builder()
                    .query(errorSummary)
                    .rewrittenQuery(errorSummary)
                    .topK(chatProperties.getTopK())
                    .threshold(chatProperties.getSimilarityThreshold())
                    .budget(RetrievalBudget.builder()
                            .recallBudget(chatProperties.getRecallBudget())
                            .candidateLimit(chatProperties.getCandidateLimit())
                            .contextTopK(chatProperties.getContextTopK())
                            .build())
                    .metadata(Map.of("intent", "DIAGNOSE"))
                    .build();
            List<RetrievedChunk> chunks = retrievalEngine.retrieve(ctx).getFinalChunks();
            List<RelatedDoc> docs = new ArrayList<>(chunks.size());
            for (RetrievedChunk c : chunks) {
                Map<String, Object> meta = c.getMetadata();
                String docName = meta == null ? "?" : str(meta.get("doc_name"));
                String outline = meta == null ? "" : str(meta.get("outline_path"));
                String preview = c.getContent() == null ? "" : truncate(c.getContent(), PREVIEW_CAP);
                double score = c.getScore() == null ? 0.0 : c.getScore();
                docs.add(new RelatedDoc(docName, outline, preview, score));
            }
            return docs;
        } catch (Exception e) {
            log.warn("[诊断] 文档检索异常，跳过检索环节: {}", e.getMessage());
            return List.of();
        }
    }

    /** LLM 诊断：报错 + 检索文档 → 根因 + 修复建议（Markdown） */
    private String generateSuggestion(String errorSummary, List<RelatedDoc> docs) {
        try {
            String systemPrompt = promptStore.raw("diagnose/log-diagnose");
            String docsText = docs.isEmpty() ? "（未检索到相关文档）" : buildDocsText(docs);
            String userMsg = "<error>\n" + errorSummary + "\n</error>\n\n<documents>\n" + docsText + "\n</documents>";
            return diagnoseChatClient.prompt()
                    .system(systemPrompt)
                    .user(userMsg)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("[诊断] LLM 建议生成失败", e);
            return "建议生成失败: " + e.getMessage()
                    + "\n\n可参考上方「报错」与「相关文档」自行判断。";
        }
    }

    private String buildDocsText(List<RelatedDoc> docs) {
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (RelatedDoc d : docs) {
            sb.append("[").append(i++).append("] 来源: ").append(d.docName());
            if (StringUtils.hasText(d.outlinePath())) {
                sb.append(" | ").append(d.outlinePath());
            }
            sb.append("\n").append(d.contentPreview()).append("\n\n");
            if (sb.length() > DOCS_CONTEXT_CAP) {
                sb.append("...(更多文档已截断)");
                break;
            }
        }
        return sb.toString().trim();
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
