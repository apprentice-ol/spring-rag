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
import com.nageoffer.ai.rag.ingestion.domain.entity.DocumentEntity;
import com.nageoffer.ai.rag.ingestion.mapper.DocumentMapper;
import com.nageoffer.ai.rag.chat.intent.IntentClassifier;
import com.nageoffer.ai.rag.chat.intent.IntentResult;
import com.nageoffer.ai.rag.chat.normalize.QueryRewriter;
import com.nageoffer.ai.rag.chat.retrieval.RetrievalBudget;
import com.nageoffer.ai.rag.chat.retrieval.RetrievedChunk;
import com.nageoffer.ai.rag.chat.retrieval.SearchContext;
import com.nageoffer.ai.rag.chat.service.RagAnswerStreamService;
import com.nageoffer.ai.rag.chat.util.TextPreviews;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
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
    private final DocumentMapper documentMapper;

    /** 流式回答落库/收尾专用（虚拟线程）：complete 回调跑在 reactor 事件循环上，阻塞 JDBC 必须移出 */
    @Qualifier("chatPersistExecutor")
    private final ExecutorService chatPersistExecutor;

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
        String otelTraceId = currentTraceId();
        String ruleNormalized = obsTemplate.step("rag.query.normalize", question, () -> QueryNormalizer.normalize(question));
        if (ruleNormalized.isBlank()) {
            ruleNormalized = question == null ? "" : question.trim();
        }

        // ========== 1. traceId 提取前置（纯正则零开销）：命中直接进诊断，省掉 rewrite+classify 两次 LLM 往返 ==========
        String traceId = TraceIdExtractor.extract(question);
        if (traceId != null) {
            log.info("[对话管道] 消息含 traceId，直接进诊断分支: traceId={}", traceId);
            handleDiagnose(traceId, conversationId, emitter, otelTraceId);
            return;
        }

        // ========== 2. 指代悬空检测（纯正则 + 一次轻查询）：「这篇文档」类指代在会话里找不到先行对象时
        // 反问澄清，而不是把"这篇"静默解释成检索第一名（检索到哪篇就总结哪篇，用户易被误导） ==========
        if (isDanglingDocReference(question, conversationId)) {
            log.info("[对话管道] 指代悬空（会话无文档语境），反问澄清: question=\"{}\"", question);
            askForDocClarification(conversationId, emitter, otelTraceId);
            return;
        }

        // ========== 3. 意图分类（吃规则归一化结果；对改写不敏感）==========
        IntentResult intent = intentClassifier.classify(ruleNormalized);
        log.info("[对话管道] 意图分类结果: intent={}, confidence={}, needsRetrieval={}, needsDiagnose={}, reason={}",
                intent.getIntent(), intent.getConfidence(), intent.isNeedsRetrieval(), intent.isNeedsDiagnose(), intent.getReason());

        if (intent.isNeedsDiagnose()) {
            log.info("[对话管道] 命中诊断分支: needsDiagnose=true");
            handleDiagnose(null, conversationId, emitter, otelTraceId);
            return;
        }

        // ========== 4. 非查询类短路由（闲聊短路，不做 LLM 改写）==========
        if (!intent.isNeedsRetrieval()) {
            log.info("[对话管道] 非查询意图，走闲聊回复");
            handleNonQuery(question, conversationId, emitter, otelTraceId);
            return;
        }

        // ========== 5. 需要检索：才做历史加载 + LLM 改写 ==========
        String historyForRewrite = buildHistoryContext(conversationId);
        String query = queryRewriter.rewrite(ruleNormalized, historyForRewrite);
        log.info("[对话管道] 会话ID={}, 原始={}, 规则归一化={}, LLM改写={}",
                conversationId, question, ruleNormalized, query);

        // ========== 6. Agent 编排检索（可插拔，默认 naive = 单次检索）==========
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

        // ========== 7. 空检索处理 ==========
        if (agentResult.verdict() == RetrievalVerdict.EMPTY || agentResult.isEmpty()) {
            log.warn("[对话管道] 检索结果为空(verdict={})，返回降级提示", agentResult.verdict());
            Long emptyMsgId = handleRetrievalEmpty(question, conversationId, emitter, otelTraceId);
            agentTraceService.record(conversationId, emptyMsgId, ragAgent.getType(), question, agentResult.trace(), otelTraceId);
            return;
        }

        // ========== 8. 构建上下文 + 流式回答 ==========
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

    /** 文档类指代词模式：这篇/该/上述/刚才的… + 文档/文件/文章/发票等（命中才做悬空判定，无命中零成本） */
    private static final java.util.regex.Pattern DANGLING_DOC_REF = java.util.regex.Pattern.compile(
            "(?:这篇|这封|这份|这则|该|上述|上面的|上面提到|刚才|刚刚|前面|之前)"
                    + "(?:一?(?:篇|封|份|则)|的)?\\s*"
                    + "(?:文档|文件|文章|资料|报告|报表|表格|发票|合同|手册|指南|规范)");

    /**
     * 「以文档为对象」的元问题模式：问题核心是某个文档容器的摘要/要点本身（总结文档、文档里的关键信息…），
     * 没有任何业务主题词。此类问题同样需要明确的文档对象，与「这篇文档」指代同等对待。
     */
    private static final java.util.regex.Pattern META_DOC_QUESTION = java.util.regex.Pattern.compile(
            "(?:总结|概括|概述|归纳|提炼|提取|梳理)\\s*一?下?"
                    + "|(?:关键信息|核心要点|主要内容|重点内容|信息要点|核心内容)"
                    + "|(?:讲了什么|说了什么|提到(?:了)?(?:哪些|什么)|包含(?:了)?(?:哪些|什么))");

    /** 含文档类名词（元问题判定的前提：问题里根本没有"文档"字样就不可能是文档元问题） */
    private static final java.util.regex.Pattern DOC_NOUN = java.util.regex.Pattern.compile(
            "文档|文件|文章|资料");

    /**
     * 指代悬空判定：消息含「这篇文档」类指代、或是以文档为对象的元问题（无业务主题），但会话里找不到先行对象。
     * <p>先行对象 = 此前 user 消息中出现过库内任一文档名（全名或去扩展名主干）。
     * 新会话（此前无 user 消息）必悬空。仅正则命中时才做两次轻查询，正常消息零开销。</p>
     */
    private boolean isDanglingDocReference(String question, String conversationId) {
        if (question == null
                || (!DANGLING_DOC_REF.matcher(question).find() && !isMetaDocQuestion(question))) {
            return false;
        }
        // 当前 user 消息已在 execute 开头落库，count <= 1 说明这是会话首条 → 必悬空
        Long priorUserMsgs = messageMapper.selectCount(Wrappers.lambdaQuery(MessageEntity.class)
                .eq(MessageEntity::getConversationId, conversationId)
                .eq(MessageEntity::getRole, "user"));
        if (priorUserMsgs == null || priorUserMsgs <= 1) {
            return true;
        }
        // 有历史：拼最近 user 消息文本，与库内文档名比对（含去扩展名主干，用户提名字常不带后缀）
        String recentUserText = messageMapper.selectList(Wrappers.lambdaQuery(MessageEntity.class)
                        .select(MessageEntity::getContent)
                        .eq(MessageEntity::getConversationId, conversationId)
                        .eq(MessageEntity::getRole, "user")
                        .orderByDesc(MessageEntity::getId)
                        .last("LIMIT 10"))
                .stream()
                .map(MessageEntity::getContent)
                .reduce("", (a, b) -> a + "\n" + (b == null ? "" : b));
        List<String> docNames = documentMapper.selectList(Wrappers.lambdaQuery(DocumentEntity.class)
                        .select(DocumentEntity::getName))
                .stream()
                .map(DocumentEntity::getName)
                .filter(Objects::nonNull)
                .toList();
        for (String name : docNames) {
            if (recentUserText.contains(name)) {
                return false;
            }
            int dot = name.lastIndexOf('.');
            if (dot > 0 && name.length() > 4) {
                String stem = name.substring(0, dot);
                // 主干太短（如 "a"、"doc"）会大量误匹配，跳过
                if (stem.length() >= 4 && recentUserText.contains(stem)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 元问题判定：含文档类名词 + 命中元问题模式（总结/关键信息/讲了什么…）。
     * <p>例：「文档中提到了哪些关键信息」（空状态页建议词）、「总结一下这份文件」。
     * 反例（不拦）：「发票文档里提到了冲红流程」（"提到了"后跟具体主题，非"哪些/什么"泛问）、
     * 「总结一下增值税发票冲红」（不含文档名词，自带主题，正常检索）。</p>
     */
    private static boolean isMetaDocQuestion(String question) {
        return DOC_NOUN.matcher(question).find() && META_DOC_QUESTION.matcher(question).find();
    }

    /** 指代悬空的反问澄清（同诊断缺 traceId 的反问模式：落库 + 流式输出 + meta + complete）。 */
    private void askForDocClarification(String conversationId, SseEmitter emitter, String otelTraceId) {
        String ask = "你的问题指向某篇具体的文档，但我们当前的对话里还没有确定是哪一篇。\n\n"
                + "可以带上文档名提问，比如「总结《增值税发票冲红流程.pdf》」；"
                + "也可以描述得更具体一些（比如文档的主题、里面提到的内容），我来帮你定位。";
        Long msgId = saveMessage(conversationId, "assistant", ask);
        log.conversationOutput(ask);
        sendEvent(emitter, ask);
        sendMetaEvent(emitter, msgId, otelTraceId, null);
        completeEmitter(emitter);
    }

    /**
     * 处理问候/闲聊（不检索，直接回答）。
     */
    private void handleNonQuery(String question, String conversationId, SseEmitter emitter, String otelTraceId) {
        log.info("[对话管道] 走闲聊回复: question=\"{}\"", question);

        StringBuilder fullAnswer = new StringBuilder();
        // 流式回调线程（reactor-netty）的 ambient HOLDER 常未恢复，subscribe 前于业务线程捕获 output sink
        Consumer<Object> outputSink = log.conversationSink();
        Disposable disposable = ragAnswerStreamService.chitchat(question, conversationId)
                .subscribe(
                        content -> {
                            // 只累积 + 增量发 SSE；trace output 是覆盖写（last-write-wins），
                            // 每 token 全量 accept 与完成时一次 accept 的最终结果等价，却每 token 白做一遍序列化
                            fullAnswer.append(content);
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
                            // 先写 trace output 再收尾：断连时 span 可能提前结束，晚了 output 丢失
                            outputSink.accept(answer);
                            // 落库/收尾移出 reactor 事件循环线程
                            chatPersistExecutor.execute(() -> {
                                Long msgId = null;
                                try {
                                    msgId = CompletableFuture.supplyAsync(
                                            () -> saveMessage(conversationId, "assistant", answer),
                                            chatPersistExecutor)
                                            .get(5, TimeUnit.SECONDS);
                                } catch (Exception persistEx) {
                                    log.warn("[ChatPipeline] persist chat message failed", persistEx);
                                }
                                sendMetaEvent(emitter, msgId, otelTraceId, null);
                                completeEmitter(emitter);
                            });
                        });
        cancelOnDisconnect(emitter, disposable, outputSink, fullAnswer, conversationId);
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
        RagContext ragContext = buildContextText(chunks);
        log.info("[对话管道] 给 LLM 的上下文: {}块{}文档 {}字符",
                chunks.size(), ragContext.docCount(), ragContext.text().length());

        // 引用映射先行下发（流式开始前，与 trace 事件同模式）：正文里的 [N] 角标靠它渲染溯源
        String citationsJson = toJsonOrNull(ragContext.citations());
        sendCitationsEvent(emitter, citationsJson);

        StringBuilder fullAnswer = new StringBuilder();
        // 流式回调线程（reactor-netty）的 ambient HOLDER 常未恢复，subscribe 前于业务线程捕获 output sink
        Consumer<Object> outputSink = log.conversationSink();
        Disposable disposable = ragAnswerStreamService.answer(question, ragContext.text())
                .subscribe(
                        content -> {
                            // 只累积 + 增量发 SSE；trace output 是覆盖写（last-write-wins），
                            // 每 token 全量 accept 与完成时一次 accept 的最终结果等价，却每 token 白做一遍
                            // Gson 序列化 + truncate + 写属性，且 O(n²) 复制累积全文
                            fullAnswer.append(content);
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
                            // 先写 trace output 再收尾：断连时 span 可能提前结束，晚了 output 丢失
                            try {
                                outputSink.accept(answer);
                            } catch (Throwable t) {
                                log.warn("[ObservationPipeline] 写最终 trace output 失败", t);
                            }
                            // saveMessage/agentTrace 是阻塞 JDBC，移出 reactor 事件循环线程（否则拖慢所有并发流）
                            chatPersistExecutor.execute(() -> {
                                Long msgId = null;
                                try {
                                    msgId = CompletableFuture.supplyAsync(
                                            () -> saveMessage(conversationId, "assistant", answer, citationsJson),
                                            chatPersistExecutor)
                                            .get(5, TimeUnit.SECONDS);
                                } catch (Throwable t) {
                                    log.warn("[对话管道] 保存助手消息失败", t);
                                }
                                final Long traceMsgId = msgId;
                                try {
                                    CompletableFuture.supplyAsync(
                                            () -> {
                                                agentTraceService.record(conversationId, traceMsgId, paradigm, question, trace, otelTraceId);
                                                return null;
                                            },
                                            chatPersistExecutor)
                                            .get(5, TimeUnit.SECONDS);
                                } catch (Throwable t) {
                                    log.warn("[对话管道] 记录 agent trace 失败", t);
                                }
                                log.info("========== [对话管道] 完成 ========== 会话ID={}, 耗时={}ms",
                                        conversationId, System.currentTimeMillis() - t0);
                                // meta（messageId/traceId/paradigm）须在 complete 前 send：complete 后 emitter 关闭
                                sendMetaEvent(emitter, msgId, otelTraceId, paradigm);
                                completeEmitter(emitter);
                            });
                        });
        cancelOnDisconnect(emitter, disposable, outputSink, fullAnswer, conversationId);
    }

    /**
     * 断连/超时取消 LLM 流订阅（省后续 token 计费）。
     * <p>dispose 前把已累积的部分写入 trace output——流被取消后 complete 回调不会再执行，
     * 不补写的话该轮 trace 只剩 input。正常完成时 Flux 已 disposed，回调内直接返回（no-op）。</p>
     */
    private void cancelOnDisconnect(SseEmitter emitter, Disposable disposable,
                                    Consumer<Object> outputSink, StringBuilder fullAnswer,
                                    String conversationId) {
        Runnable cancel = () -> {
            if (disposable.isDisposed()) {
                return;
            }
            disposable.dispose();
            String partial = fullAnswer.toString();
            if (!partial.isEmpty()) {
                try {
                    outputSink.accept(partial);
                } catch (Throwable t) {
                    log.warn("[ObservationPipeline] 取消订阅时写 trace output 失败", t);
                }
            }
            log.warn("[对话管道] SSE 断连/超时，已取消 LLM 流, 会话ID={}, 已生成 {} 字符", conversationId, partial.length());
            completeEmitter(emitter);
        };
        emitter.onCompletion(cancel);
        emitter.onTimeout(cancel);
        emitter.onError(t -> cancel.run());
    }




    /** 检索上下文组装结果：text = 拼好的上下文，docCount = 命中文档数，citations = 引用溯源映射 */
    private record RagContext(String text, int docCount, List<Citation> citations) {
    }

    /** 回答引用溯源项：ref 对应提示词里 {@code <content ref="N">} 的 N，前端据此渲染 [N] 角标与来源面板 */
    public record Citation(int ref, String docId, String docName, int chunkCount,
                           String preview, String sourceLocation) {
    }

    /**
     * 组装检索上下文（对齐 ragent DefaultContextFormatter 的按文档分组 + 阅读顺序还原）。
     * <p>一个 {@code <content ref="N">} = 一份文档的全部命中块按 {@code chunk_index} 拼接后的连续文本
     * （ragent 的 kb-doc-block：joinDocBody 以换行顺次拼接，docs 之间保持相关性顺序）。
     * LLM 把每个 content 当作一份完整资料、跨片段连贯理解；chunk 级平铺会把同一文档相邻块拆成
     * 独立"资料"，提示词"不同 content 之间注意张冠李戴"会压制跨块整合，块间关联丢失。
     * 刻意不注入文档名与分数：标题/分数进入上下文会诱导"出自《XX》"或偏向高分块。</p>
     */
    private RagContext buildContextText(List<RetrievedChunk> chunks) {
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
        List<Citation> citations = new ArrayList<>(byDoc.size());
        Map<String, String> sourceLocations = loadSourceLocations(byDoc.keySet());
        int idx = 1;
        for (Map.Entry<String, List<RetrievedChunk>> entry : byDoc.entrySet()) {
            List<RetrievedChunk> group = entry.getValue();
            group.sort(Comparator.comparingInt(this::chunkIndexOf));
            sb.append("<content ref=\"").append(idx).append("\">\n");
            for (RetrievedChunk c : group) {
                sb.append(c.getContent() == null ? "" : c.getContent()).append("\n");
            }
            sb.append("</content>\n");
            citations.add(buildCitation(idx, entry.getKey(), group, sourceLocations));
            idx++;
        }
        return new RagContext(sb.append("</documents>").toString(), byDoc.size(), citations);
    }

    /** 组装单条引用溯源项：预览取该文档首个命中块前 240 字（压空白；右侧抽屉展示用） */
    private Citation buildCitation(int ref, String docKey, List<RetrievedChunk> group,
                                   Map<String, String> sourceLocations) {
        String docId = docKey.startsWith("__nodoc__") ? null : docKey;
        String preview = TextPreviews.preview(group.get(0).getContent(), 240);
        return new Citation(ref, docId, docNameOf(group.get(0)), group.size(), preview,
                docId != null ? sourceLocations.get(docId) : null);
    }

    /** chunk 来源文档名（doc_name 元数据；与 MultiChannelRetrievalEngine 日志口径一致） */
    private static String docNameOf(RetrievedChunk c) {
        Object v = c.getMetadata() == null ? null : c.getMetadata().get("doc_name");
        return v != null ? String.valueOf(v) : "未知文档";
    }

    /** 批量预查文档公开预览 URL（sa_document.source_location → docId），一次 IN 免逐文档 N+1 */
    private Map<String, String> loadSourceLocations(Set<String> docKeys) {
        List<String> docIds = docKeys.stream().filter(k -> !k.startsWith("__nodoc__")).toList();
        if (docIds.isEmpty()) {
            return Map.of();
        }
        try {
            return documentMapper.selectList(Wrappers.lambdaQuery(DocumentEntity.class)
                            .in(DocumentEntity::getDocId, docIds)
                            .select(DocumentEntity::getDocId, DocumentEntity::getSourceLocation))
                    .stream()
                    .filter(d -> d.getSourceLocation() != null)
                    .collect(java.util.stream.Collectors.toMap(DocumentEntity::getDocId,
                            DocumentEntity::getSourceLocation));
        } catch (Exception e) {
            log.warn("[对话管道] 批量查询引用文档原文链接失败: {}", e.getMessage());
            return Map.of();
        }
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

    /** 发送引用溯源映射（SSE citations 事件，流式开始前一次性）：ref → 文档信息，前端渲染 [N] 角标与来源面板。 */
    private void sendCitationsEvent(SseEmitter emitter, String citationsJson) {
        if (citationsJson == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name("citations").data(citationsJson));
        } catch (Exception e) {
            log.debug("[对话管道] citations 事件发送失败（忽略）: {}", e.getMessage());
        }
    }

    /** 序列化失败返回 null（引用溯源是增强功能，绝不能阻断回答主链路） */
    private String toJsonOrNull(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("[对话管道] citations 序列化失败（忽略）: {}", e.getMessage());
            return null;
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
                        // 同秒多条消息时以 id 决胜，保证多轮改写上下文顺序稳定
                        .orderByDesc(MessageEntity::getId)
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

    /**
     * 确保会话存在并刷新 updated_at。
     * <p>热路径（已存在）走单语句 UPDATE 免取整行；不存在再插入，并发首条消息的
     * check-then-insert 竞态由 conversation_id 唯一约束兜底（DuplicateKeyException 幂等忽略）。</p>
     */
    private void ensureConversation(String conversationId, String firstQuestion) {
        int updated = conversationMapper.update(null,
                Wrappers.lambdaUpdate(ConversationEntity.class)
                        .eq(ConversationEntity::getConversationId, conversationId)
                        .set(ConversationEntity::getUpdatedAt, LocalDateTime.now()));
        if (updated > 0) {
            return;
        }
        ConversationEntity conversationEntity = new ConversationEntity();
        conversationEntity.setConversationId(conversationId);
        // 用首条问题前 30 字作为标题
        String title = firstQuestion == null ? "新对话" : firstQuestion.trim();
        if (title.length() > 30) {
            title = title.substring(0, 30);
        }
        conversationEntity.setTitle(title);
        conversationEntity.setCreatedAt(LocalDateTime.now());
        conversationEntity.setUpdatedAt(LocalDateTime.now());
        try {
            conversationMapper.insert(conversationEntity);
        } catch (DuplicateKeyException e) {
            // 并发首条消息：另一请求已插入，幂等
        }
    }

    /**
     * 保存一条消息并返回其 id。
     * <p>会话 updated_at 由 {@link #ensureConversation} 在请求入口统一刷新，此处不再重复回写
     * （原实现每条消息多 2 次 DB 往返：select 整行 + update）。</p>
     */
    private Long saveMessage(String conversationId, String role, String content) {
        return saveMessage(conversationId, role, content, null);
    }

    /** 同上，带引用溯源 JSON（仅 RAG assistant 消息非空）。 */
    private Long saveMessage(String conversationId, String role, String content, String citationsJson) {
        if (content == null || content.isBlank()) {
            return null;
        }
        MessageEntity messageEntity = new MessageEntity();
        messageEntity.setConversationId(conversationId);
        messageEntity.setRole(role);
        messageEntity.setContent(content);
        messageEntity.setCitations(citationsJson);
        messageEntity.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(messageEntity);
        return messageEntity.getId();
    }
}
