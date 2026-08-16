package com.nageoffer.ai.rag.chat.service.pipeline;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.ai.llmobservability.observation.TelemetryTemplate;
import com.jjx.ai.llmobservability.observation.logging.TelemetryLogger;
import com.nageoffer.ai.rag.chat.agent.*;
import com.nageoffer.ai.rag.chat.dao.entity.ConversationEntity;
import com.nageoffer.ai.rag.chat.dao.entity.MessageEntity;
import com.nageoffer.ai.rag.chat.dao.mapper.ConversationMapper;
import com.nageoffer.ai.rag.chat.dao.mapper.MessageMapper;
import com.nageoffer.ai.rag.chat.intent.IntentClassifier;
import com.nageoffer.ai.rag.chat.intent.IntentResult;
import com.nageoffer.ai.rag.chat.normalize.QueryRewriter;
import com.nageoffer.ai.rag.chat.retrieval.RetrievalBudget;
import com.nageoffer.ai.rag.chat.retrieval.RetrievedChunk;
import com.nageoffer.ai.rag.chat.retrieval.SearchContext;
import com.nageoffer.ai.rag.chat.service.RagAnswerStreamService;
import com.nageoffer.ai.rag.chat.util.TraceIdExtractor;
import com.nageoffer.ai.rag.common.util.QueryNormalizer;
import com.nageoffer.ai.rag.config.prompt.PromptStore;
import com.nageoffer.ai.rag.config.properties.AgentProperties;
import com.nageoffer.ai.rag.config.properties.ChatProperties;
import com.nageoffer.ai.rag.diagnose.dto.*;
import com.nageoffer.ai.rag.diagnose.service.LogDiagnoseService;
import com.jjx.ai.llmobservability.backends.openobserve.dto.TraceLogEntry;
import io.opentelemetry.api.trace.Span;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.util.Strings;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;

/**
 * 流式对话管道（StreamChatPipeline）。
 * <p>
 * 替代原有的 {@link com.nageoffer.ai.rag.chat.service.impl.DefaultChatService} 单路方案，
 * 采用多阶段流水线编排：
 * </p>
 *
 * <ol>
 *   <li><b>loadMemory</b> — 通过 ChatMemory 加载历史对话</li>
 *   <li><b>intentClassify</b> — LLM 意图分类，判断是否需要检索 / 是否走日志诊断</li>
 *   <li><b>diagnose</b> — 命中诊断意图（或消息含 traceId）时查日志/报错/建议并流式输出（短路）</li>
 *   <li><b>handleNonQuery</b> — 问候/闲聊直接回复（短路）</li>
 *   <li><b>retrieve</b> — 多通道检索（向量 + 关键词 + 联网）</li>
 *   <li><b>handleEmptyRetrieval</b> — 无结果时降级回复</li>
 *   <li><b>streamRagResponse</b> — 构建含上下文的 Prompt，流式输出</li>
 * </ol>
 *
 * <p>简化迁移自 {@code 15_ragent} 的 StreamChatPipeline，适配 Spring AI 技术栈。</p>
 */
@Service
@RequiredArgsConstructor
public class StreamChatPipeline {

    private static final TelemetryLogger log = TelemetryLogger.of(StreamChatPipeline.class);

    private final IntentClassifier intentClassifier;
    private final AgentRegistry agentRegistry;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;
    private final ChatProperties chatProperties;
    private final PromptStore promptStore;
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final QueryRewriter queryRewriter;
    private final LogDiagnoseService logDiagnoseService;
    private final AgentTraceService agentTraceService;
    private final RagAnswerStreamService ragAnswerStreamService;
    private final TelemetryTemplate obsTemplate;

    /**
     * 执行流式对话管道。
     *
     * @param question       用户问题
     * @param conversationId 会话 ID
     * @param emitter        SSE 输出
     */
    public void execute(String question, String conversationId, String agent, SseEmitter emitter) {
        ensureConversation(conversationId, question);
        saveMessage(conversationId, "user", question);
        long t0 = System.currentTimeMillis();
        // 当前请求的 OTel traceId（rag.chat 根 span）：须在业务线程取好传下去——
        // 流式回调跑在 reactor-netty 线程，ambient context 不保证恢复；span 结束后 SpanContext 仍可读
        String otelTraceId = currentTraceId();
        // 0. 查询归一化：① 规则式（去噪 + 疑问→陈述）→ ② LLM 改写（带历史上下文消解指代词）
        // QueryNormalizer 是 rag-common 静态工具（AOP 够不到），手动开 step 埋点
        String ruleNormalized = obsTemplate.step("rag.query.normalize", question, () -> QueryNormalizer.normalize(question));

        if (ruleNormalized.isBlank()) {
            ruleNormalized = question == null ? "" : question.trim();
        }
        String historyForRewrite = buildHistoryContext(conversationId);
        String query = queryRewriter.rewrite(ruleNormalized, historyForRewrite);
        log.info("[对话管道] 会话ID={}, 原始={}, 规则归一化={}, LLM改写={}",
                conversationId, question, ruleNormalized, query);

        IntentResult intent = intentClassifier.classify(query);
        log.info("[对话管道] 意图分类结果: intent={}, confidence={}, needsRetrieval={}, needsDiagnose={}, reason={}",
                intent.getIntent(), intent.getConfidence(), intent.isNeedsRetrieval(), intent.isNeedsDiagnose(), intent.getReason());


        String traceId = TraceIdExtractor.extract(question);
        if (traceId != null || intent.isNeedsDiagnose()) {
            log.info("[对话管道] 命中诊断分支: traceId={}, needsDiagnose={}", traceId, intent.isNeedsDiagnose());
            handleDiagnose(traceId, conversationId, emitter, otelTraceId);
            return;
        }

        // ========== 2. 非查询类短路由 ==========
        if (!intent.isNeedsRetrieval()) {
            log.info("[对话管道] 非查询意图，走闲聊回复");
            handleNonQuery(question, conversationId, emitter, otelTraceId);
            return;
        }

        // ========== 3. Agent 编排检索（可插拔，默认 naive = 单次检索）==========
        long tRetrieve = System.currentTimeMillis();
        SearchContext searchCtx = SearchContext.builder()
                .query(question)
                .rewrittenQuery(query)
                .topK(chatProperties.getTopK())
                .threshold(chatProperties.getSimilarityThreshold())
                .budget(RetrievalBudget.builder()
                        .recallBudget(chatProperties.getRecallBudget())
                        .candidateLimit(chatProperties.getCandidateLimit())
                        .contextTopK(chatProperties.getContextTopK())
                        .build())
                .metadata(Map.of(
                        "intent", intent.getIntent(),
                        "needsWebSearch", intent.isNeedsWebSearch()))
                .build();

        log.info("[对话管道] 开始检索(agent={}): topK={}, 阈值={}, recallBudget={}, candidateLimit={}, contextTopK={}",
                agentProperties.paradigmEnum().getCode(),
                chatProperties.getTopK(), chatProperties.getSimilarityThreshold(),
                chatProperties.getRecallBudget(), chatProperties.getCandidateLimit(), chatProperties.getContextTopK());

        // 检索编排委托给可插拔 agent：请求级 ?agent= 覆盖默认范式（naive/react）
        RagAgent ragAgent = StringUtils.hasText(agent)
                ? agentRegistry.require(agent)
                : agentRegistry.defaultAgent();
        // trace 维度（intent/agent 范式）由 TelemetryDimensions 从 step 返回值自动提取，此处零观测调用
        AgentRequest agentReq = new AgentRequest(question, historyForRewrite, intent, searchCtx, agentProperties, null);
        AgentRetrievalResult agentResult = ragAgent.planAndRetrieve(agentReq);
        log.info("[对话管道] agent={} 完成: verdict={}, 最终块={}条, 检索耗时={}ms, llm调用={}次",
                ragAgent.getType(), agentResult.verdict(),
                agentResult.finalChunks().size(),
                System.currentTimeMillis() - tRetrieve,
                agentResult.trace().getLlmCallCount());

        // 发送 agent 执行轨迹（SSE trace 事件，前端对照面板用；一次性，在流式回答前发出）
        sendObsEvent(emitter, agentResult.trace());

        // ========== 4. 空检索处理 ==========
        if (agentResult.verdict() == RetrievalVerdict.EMPTY || agentResult.isEmpty()) {
            log.warn("[对话管道] 检索结果为空(verdict={})，返回降级提示", agentResult.verdict());
            Long emptyMsgId = handleRetrievalEmpty(question, conversationId, emitter, otelTraceId);
            agentTraceService.record(conversationId, emptyMsgId, ragAgent.getType(), question, agentResult.trace(), otelTraceId);
            return;
        }

        // ========== 5. 构建上下文 + 流式回答 ==========
        log.info("[对话管道] 开始流式回答, 上下文共 {} 条", agentResult.finalChunks().size());
        streamRagResponse(question, conversationId, agentResult.finalChunks(), emitter, t0, ragAgent.getType(), agentResult.trace(), otelTraceId);
    }

    // ==================== 日志诊断分支 ====================

    /**
     * 日志诊断分支：traceId 缺失则反问；有则查日志/报错/相关文档/建议，分段流式输出。
     * <p>复用 {@link LogDiagnoseService#diagnose}（查 OpenObserve 日志 → 提报错 → 检索知识库 → LLM 建议）。
     * <p>诊断含 LLM 调用（约 10–30s），先发即时提示再分段输出结果。
     */
    private void handleDiagnose(String traceId, String conversationId, SseEmitter emitter, String otelTraceId) {
        // traceId 缺失 → 反问
        if (traceId == null) {
            String ask = "请提供要排查的 traceId（从应用日志的 `[traceId,spanId]` 复制，32 位十六进制）。";
            log.info("[对话管道] 诊断缺 traceId，反问用户");
            Long msgId = saveMessage(conversationId, "assistant", ask);
            log.conversationOutput(ask);
            sendEvent(emitter, ask);
            sendMetaEvent(emitter, msgId, otelTraceId, null);
            completeEmitter(emitter);
            return;
        }
        sendEvent(emitter, "正在排查 traceId `" + traceId + "` …\n\n");
        log.info("[对话管道] 开始诊断 traceId={}", traceId);

        // ① 轻量预览（查日志+报错，无 LLM）
        DiagnosePreview preview = logDiagnoseService.preview(traceId);

        // ② 匹配校验：若用户上一轮问过具体业务（如"发票冲红"），核对 traceId 报错是否对应
        String userContext = extractUserContext(traceId, conversationId);
        if (StringUtils.hasText(userContext)) {
            MatchResult match = logDiagnoseService.checkMatch(userContext, preview);
            if (!match.relevant()) {
                String brief = StringUtils.hasText(match.errorBrief()) ? match.errorBrief() : "未知报错";
                String ask = "你给的 traceId（`" + traceId + "`）对应的报错是「" + brief
                        + "」，和你问的「" + userContext + "」看起来不是同一回事。\n\n"
                        + "要继续排查这个 traceId 吗？还是换一个和「" + userContext + "」相关的 traceId？";
                log.info("[对话管道] 诊断匹配校验不通过，反问: brief={}", brief);
                Long msgId = saveMessage(conversationId, "assistant", ask);
                log.conversationOutput(ask);
                sendEvent(emitter, ask);
                sendMetaEvent(emitter, msgId, otelTraceId, null);
                completeEmitter(emitter);
                return;
            }
            log.info("[对话管道] 诊断匹配校验通过（{}），继续完整诊断", match.reason());
        }

        // ③ 完整诊断（检索+建议）+ 分段流式
        DiagnoseResponse resp = logDiagnoseService.complete(preview);
        String answer = formatDiagnose(resp);
        Long msgId = saveMessage(conversationId, "assistant", answer);
        log.conversationOutput(answer);
        for (String segment : splitSegments(answer)) {
            sendEvent(emitter, segment);
        }
        sendMetaEvent(emitter, msgId, otelTraceId, null);
        completeEmitter(emitter);
    }

    /**
     * 提取用户业务语境：当前消息含 traceId 时，从会话历史取最近一条非 traceId 的 user 消息
     * （如"我发票冲红为什么报错了"），供匹配校验。无此类消息则返回 null（跳过校验，直接诊断）。
     */
    private String extractUserContext(String currentTraceId, String conversationId) {
        if (currentTraceId == null) {
            return null;
        }
        List<MessageEntity> recent = messageMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MessageEntity>()
                        .eq(MessageEntity::getConversationId, conversationId)
                        .eq(MessageEntity::getRole, "user")
                        .orderByDesc(MessageEntity::getCreatedAt)
                        .last("LIMIT 6"));
        if (recent == null) {
            return null;
        }
        for (MessageEntity m : recent) {
            String c = m.getContent();
            // 跳过当前那条只含 traceId 的消息，取最近的业务问题描述
            if (c == null || TraceIdExtractor.extract(c) != null) {
                continue;
            }
            String trimmed = c.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return null;
    }

    /** 把诊断结果组织成可读 Markdown（按 ## 分段，配合 {@link #splitSegments}） */
    private String formatDiagnose(DiagnoseResponse resp) {
        StringBuilder sb = new StringBuilder();
        sb.append("已定位 traceId `").append(resp.traceId()).append("`（")
                .append(resp.logs() == null ? 0 : resp.logs().size()).append(" 条日志，")
                .append(resp.errors() == null ? 0 : resp.errors().size()).append(" 处报错）\n\n");

        if (resp.errors() != null && !resp.errors().isEmpty()) {
            sb.append("## 报错摘要\n");
            int n = 0;
            for (TraceLogEntry e : resp.errors()) {
                if (n++ >= 5) {
                    sb.append("- …(更多报错已省略)\n");
                    break;
                }
                String tag = e.exceptionClass() != null ? e.exceptionClass() : e.level();
                sb.append("- **[").append(tag).append("]** ").append(safeMsg(e.message())).append("\n");
            }
            sb.append("\n");
        }

        sb.append("## 根因与建议\n")
                .append(resp.suggestion() == null || resp.suggestion().isBlank() ? "（无）" : resp.suggestion())
                .append("\n\n");

        if (resp.relatedDocs() != null && !resp.relatedDocs().isEmpty()) {
            sb.append("## 相关文档\n");
            int n = 0;
            for (RelatedDoc d : resp.relatedDocs()) {
                if (n++ >= 5) {
                    sb.append("- …(更多文档已省略)\n");
                    break;
                }
                sb.append(n).append(". ").append(d.docName() == null ? "?" : d.docName());
                if (d.outlinePath() != null && !d.outlinePath().isBlank()) {
                    sb.append(" | ").append(d.outlinePath());
                }
                sb.append("\n");
            }
        }
        return sb.toString().trim();
    }

    /** 按 ## 标题切成段落，供分段流式发送 */
    private static List<String> splitSegments(String answer) {
        List<String> segs = new ArrayList<>();
        for (String p : answer.split("(?=^## )", -1)) {
            if (!p.isBlank()) {
                segs.add(p.strip() + "\n\n");
            }
        }
        return segs;
    }

    private static String safeMsg(String msg) {

        if (msg == null) {
            return "";
        }
        String s = msg.replaceAll("\\r?\\n", " ");
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    // ==================== 其他分支 ====================

    /**
     * 处理问候/闲聊（不检索，直接回答）。
     */
    private void handleNonQuery(String question, String conversationId, SseEmitter emitter, String otelTraceId) {
        log.info("[对话管道] 走闲聊回复: question=\"{}\"", question);

        StringBuilder fullAnswer = new StringBuilder();
        // 流式回调线程（reactor-netty）的 ambient HOLDER 常未恢复，subscribe 前于业务线程捕获 output sink
        Consumer<Object> outputSink = log.conversationSink();
        ragAnswerStreamService.chitchat(question, conversationId)
                .subscribe(
                        content -> {
                            fullAnswer.append(content);
                            // 先写 trace output 再发 SSE：客户端断连时 sendEvent 可能让 server span 提前结束，
                            // 若在 error 回调再写会因 span 已结束而丢失 output。
                            outputSink.accept(fullAnswer.toString());
                            sendEvent(emitter, content);
                        },
                        e -> {
                            log.error("[对话管道] 闲聊流式输出失败", e);
                            // 异常路径补 trace output，避免闲聊流式中断时 output 丢失
                            String partial = fullAnswer.toString();
                            outputSink.accept(partial.isEmpty()
                                    ? "[stream error] " + e.getMessage() : partial);
                            completeEmitter(emitter);
                        },
                        () -> {
                            String answer = fullAnswer.toString();
                            Long msgId = saveMessage(conversationId, "assistant", answer);
                            outputSink.accept(answer);
                            sendMetaEvent(emitter, msgId, otelTraceId, null);
                            completeEmitter(emitter);
                        });
    }

    /**
     * 处理检索无结果的情况——直接回复降级消息，不走 LLM。
     *
     * @return 降级 assistant 消息 id（供调用处关联 agent trace 落库；内容为空时为 null）
     */
    private Long handleRetrievalEmpty(String question, String conversationId, SseEmitter emitter, String otelTraceId) {
        log.info("[对话管道] 检索为空，直接返回降级提示: 会话ID={}", conversationId);
        String fallbackMsg = StringUtils.hasText(chatProperties.getEmptyRetrievalMsg())
                ? chatProperties.getEmptyRetrievalMsg()
                : promptStore.raw("chat/pipeline/empty-retrieval");
        Long msgId = saveMessage(conversationId, "assistant", fallbackMsg);
        log.conversationOutput(fallbackMsg);
        sendEvent(emitter, fallbackMsg);
        sendMetaEvent(emitter, msgId, otelTraceId, null);
        completeEmitter(emitter);
        return msgId;
    }

    /**
     * 构建 RAG 上下文并流式回答。
     *
     * <p>系统提示词（回答规则）由 ragChatClient 的 defaultSystem 承载
     * （prompts/chat/pipeline/rag-answer-system.md），此处只把检索资料与用户问题组装进
     * user message——避免把每次都变的资料拼进 system 而破坏 prompt cache，也让规则稳定可缓存。</p>
     *
     * <p>流式 LLM 调用已抽到 {@link RagAnswerStreamService#answer}（{@code @TelemetryStep} 自动埋点），
     * 此处只写 SSE 副作用（sendEvent / saveMessage / completeEmitter）与 trace 级 output。</p>
     */
    private void streamRagResponse(String question, String conversationId,
                                    List<RetrievedChunk> chunks, SseEmitter emitter, long t0,
                                    String paradigm, AgentTrace trace, String otelTraceId) {
        String contextText = buildContextText(chunks);

        int docCount = contextText.split("<content ref=\"", -1).length - 1;
        log.info("[对话管道] 给 LLM 的上下文: {}块{}文档 {}字符", chunks.size(), docCount, contextText.length());

        StringBuilder fullAnswer = new StringBuilder();
        // 流式回调线程（reactor-netty）的 ambient HOLDER 常未恢复，subscribe 前于业务线程捕获 output sink
        Consumer<Object> outputSink = log.conversationSink();
        ragAnswerStreamService.answer(question, contextText)
                .subscribe(
                        content -> {
                            try {
                                fullAnswer.append(content);
                                // 先写 trace output 再发 SSE：客户端断连时 sendEvent 可能让 server span 提前结束，
                                // 若在 error 回调再写会因 span 已结束而丢失 output。
                                outputSink.accept(fullAnswer.toString());
                            } catch (Throwable t) {
                                // 观测写失败绝不能中断流式回答
                                log.warn("[ObservationPipeline] 写 trace output 失败（忽略）", t);
                            }
                            sendEvent(emitter, content);
                        },
                        e -> {
                            log.error("[ObservationPipeline] RAG 流式失败", e);
                            // 异常路径也写 trace output（已累积的部分回答 / error 标记），
                            // 否则流式中断时根 span 只剩 input、output 丢失（Langfuse trace 列表 output 为空）
                            String partial = fullAnswer.toString();
                            try {
                                outputSink.accept(partial.isEmpty()
                                        ? "[stream error] " + e.getMessage() : partial);
                            } catch (Throwable t) {
                                log.warn("[ObservationPipeline] 写 trace output 失败", t);
                            }
                            emitter.completeWithError(e);
                        },
                        () -> {
                            String answer = fullAnswer.toString();
                            Long msgId = null;
                            try {
                                msgId = saveMessage(conversationId, "assistant", answer);
                            } catch (Throwable t) {
                                log.warn("[对话管道] 保存助手消息失败", t);
                            }
                            try {
                                outputSink.accept(answer);
                            } catch (Throwable t) {
                                log.warn("[ObservationPipeline] 写最终 trace output 失败", t);
                            }
                            try {
                                agentTraceService.record(conversationId, msgId, paradigm, question, trace, otelTraceId);
                            } catch (Throwable t) {
                                log.warn("[对话管道] 记录 agent trace 失败", t);
                            }
                            log.info("========== [对话管道] 完成 ========== 会话ID={}, 耗时={}ms",
                                    conversationId, System.currentTimeMillis() - t0);
                            // meta（messageId/traceId/paradigm）须在 complete 前 send：complete 后 emitter 关闭
                            sendMetaEvent(emitter, msgId, otelTraceId, paradigm);
                            completeEmitter(emitter);
                        });
    }




    /**
     * 组装检索上下文（对齐 ragent DefaultContextFormatter 的按文档分组 + 阅读顺序还原）。
     * <p>一个 {@code <content ref="N">} = 一份文档的全部命中块按 {@code chunk_index} 拼接后的连续文本
     * （ragent 的 kb-doc-block：joinDocBody 以换行顺次拼接，docs 之间保持相关性顺序）。
     * LLM 把每个 content 当作一份完整资料、跨片段连贯理解；chunk 级平铺会把同一文档相邻块拆成
     * 独立"资料"，提示词"不同 content 之间注意张冠李戴"会压制跨块整合，块间关联丢失。
     * 刻意不注入文档名与分数：标题/分数进入上下文会诱导"出自《XX》"或偏向高分块。</p>
     */
    private String buildContextText(List<RetrievedChunk> chunks) {
        // 按 doc_id 分组（LinkedHashMap 保持首次出现顺序=相关性顺序），组内按 chunk_index 还原原文顺序；
        // doc_id 缺失的块各自单独成组（__nodoc__ + 序号），避免无关块被拼进同一份"资料"
        Map<String, List<RetrievedChunk>> byDoc = new LinkedHashMap<>();
        int anonymousSeq = 0;
        for (RetrievedChunk c : chunks) {
            String docId = docIdOf(c);
            String key = docId != null ? docId : "__nodoc__" + (anonymousSeq++);
            byDoc.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
        }
        StringBuilder sb = new StringBuilder("<documents>\n");
        int idx = 1;
        for (List<RetrievedChunk> group : byDoc.values()) {
            group.sort(Comparator.comparingInt(this::chunkIndexOf));
            sb.append("<content ref=\"").append(idx++).append("\">\n");
            for (RetrievedChunk c : group) {
                sb.append(c.getContent() == null ? "" : c.getContent()).append("\n");
            }
            sb.append("</content>\n");
        }
        return sb.append("</documents>").toString();
    }

    /** chunk 在文档内的阅读序号（metadata.chunk_index），缺失按 0 排最前 */
    private int chunkIndexOf(RetrievedChunk c) {
        Object v = c.getMetadata() == null ? null : c.getMetadata().get("chunk_index");
        if (v == null) {
            return 0;
        }
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String docIdOf(RetrievedChunk c) {
        Map<String, Object> meta = c.getMetadata();
        if (meta == null) return null;
        Object v = meta.get("doc_id");
        return v == null ? null : v.toString();
    }

    /** 发送 agent 执行轨迹（SSE trace 事件，前端对照面板渲染用；一次性，在流式回答前发出）。 */
    private void sendObsEvent(SseEmitter emitter, AgentTrace trace) {
        if (trace == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(trace);
            emitter.send(SseEmitter.event().name("trace").data(json));
        } catch (Exception e) {
            log.debug("[ObservationPipeline] trace 事件发送失败（忽略）: {}", e.getMessage());
        }
    }

    /** 当前请求的 OTel traceId（{@code rag.chat} 根 span）；仅业务线程内有效，无有效 span 时返回 null。 */
    private static String currentTraceId() {
        var ctx = Span.current().getSpanContext();
        return ctx.isValid() ? ctx.getTraceId() : null;
    }

    /**
     * 发送消息元信息（SSE meta 事件，流末尾）：messageId（关联 sa_message）/ traceId（跳 OpenObserve 全链路）/ paradigm。
     * 前端把它绑定到 assistant 气泡，实现"消息 ↔ 轨迹 ↔ OO 链路"三方关联。
     */
    private void sendMetaEvent(SseEmitter emitter, Long messageId, String traceId, String paradigm) {
        try {
            Map<String, Object> meta = new HashMap<>();
            meta.put("messageId", messageId);
            meta.put("traceId", traceId);
            meta.put("paradigm", paradigm);
            emitter.send(SseEmitter.event().name("meta").data(objectMapper.writeValueAsString(meta)));
        } catch (Exception e) {
            log.debug("[ObservationPipeline] meta 事件发送失败（忽略）: {}", e.getMessage());
        }
    }

    private void sendEvent(SseEmitter emitter, String content) {
        try {
            emitter.send(SseEmitter.event().data(content).name("message"));
        } catch (IOException e) {
            // 客户端断开，忽略
            log.debug("[ObservationPipeline] SSE 写入失败（客户端可能已断开）: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("[ObservationPipeline] SSE 发送异常", e);
        }
    }

    private void completeEmitter(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception e) {
            // 已完成或已断开，忽略
        }
    }

    // ==================== 历史上下文 ====================

    /** 加载最近 2 轮（4 条）历史消息，拼接为 LLM 上下文前缀。无历史时返回空串 */
    private String buildHistoryContext(String conversationId) {
        List<MessageEntity> recent = messageMapper.selectList(
                Wrappers.lambdaQuery(MessageEntity.class)
                        .eq(MessageEntity::getConversationId, conversationId)
                        .orderByDesc(MessageEntity::getCreatedAt)
                        .last("LIMIT 4"));
        if (recent == null || recent.isEmpty() || recent.size() < 2){
            return Strings.EMPTY;
        }
        Collections.reverse(recent);
        StringBuilder sb = new StringBuilder("历史对话：\n");
        for (MessageEntity m : recent) {
            String role = "user".equals(m.getRole()) ? "用户" : "助手";
            String c = m.getContent() == null ? "" : m.getContent();
            if (c.length() > 500){
                c = c.substring(0, 500) + "...";
            }
            sb.append(role).append("：").append(c).append("\n\n");
        }
        return sb.toString();
    }

    // ==================== 消息持久化 ====================

    private void ensureConversation(String conversationId, String firstQuestion) {
        ConversationEntity existing;
        existing = conversationMapper.selectOne(Wrappers.lambdaQuery(ConversationEntity.class).eq(ConversationEntity::getConversationId, conversationId));
        if (existing != null) {
            existing.setUpdatedAt(LocalDateTime.now());
            conversationMapper.updateById(existing);
            return;
        }
        ConversationEntity conversationEntity = new ConversationEntity();
        conversationEntity.setConversationId(conversationId);
        // 用首条问题前 30 字作为标题
        String title = firstQuestion == null ? "新对话" : firstQuestion.trim();
        if (title.length() > 30){
            title = title.substring(0, 30);
        }
        conversationEntity.setTitle(title);
        conversationEntity.setCreatedAt(LocalDateTime.now());
        conversationEntity.setUpdatedAt(LocalDateTime.now());
        conversationMapper.insert(conversationEntity);
    }

    private Long saveMessage(String conversationId, String role, String content) {
        if (content == null || content.isBlank()){
            return null;
        }
        MessageEntity messageEntity = new MessageEntity();
        messageEntity.setConversationId(conversationId);
        messageEntity.setRole(role);
        messageEntity.setContent(content);
        messageEntity.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(messageEntity);
        // 更新会话时间
        ConversationEntity conv = conversationMapper.selectOne(
                Wrappers.lambdaQuery(ConversationEntity.class).eq(ConversationEntity::getConversationId, conversationId));
        if (conv != null) {
            conv.setUpdatedAt(LocalDateTime.now());
            conversationMapper.updateById(conv);
        }
        return messageEntity.getId();
    }
}
