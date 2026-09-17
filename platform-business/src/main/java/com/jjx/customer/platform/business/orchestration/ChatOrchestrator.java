package com.jjx.customer.platform.business.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.ai.llmobservability.observation.TelemetryTemplate;
import com.jjx.ai.llmobservability.observation.logging.TelemetryLogger;
import com.jjx.customer.platform.business.FrameworkKnowledgeRunner;
import com.jjx.customer.platform.business.FrameworkOpsRunner;
import com.jjx.customer.platform.business.PromptFingerprintResolver;
import com.jjx.customer.platform.business.agents.KnowledgeFrameworkAgent;
import com.jjx.customer.platform.business.agents.OpsDiagnoseFrameworkAgent;
import com.jjx.customer.platform.business.orchestration.intent.IntentClassifier;
import com.jjx.customer.platform.business.orchestration.normalize.QueryRewriter;
import com.jjx.customer.platform.business.orchestration.rag.RagContextAssembler;
import com.jjx.customer.platform.business.runtime.DegradeGuard;
import com.jjx.customer.platform.agent.framework.session.AgentSessionState;
import com.jjx.customer.platform.agent.framework.session.SessionStore;
import com.jjx.customer.platform.business.tools.ops.OpsSlotSpecs;
import com.jjx.customer.platform.business.trace.AgentTraceService;
import com.jjx.customer.platform.business.trace.model.TraceView;
import com.jjx.customer.platform.cache.CacheFrequencyPolicy;
import com.jjx.customer.platform.cache.CacheProperties;
import com.jjx.customer.platform.cache.CacheStore;
import com.jjx.customer.platform.cache.FrequencyTracker;
import com.jjx.customer.platform.common.util.QueryNormalizer;
import com.jjx.customer.platform.common.util.TraceIdExtractor;
import com.jjx.customer.platform.config.prompt.PromptStore;
import com.jjx.customer.platform.config.properties.AgentProperties;
import com.jjx.customer.platform.config.properties.ChatProperties;
import com.jjx.customer.platform.conversation.ConversationStore;
import com.jjx.customer.platform.delivery.DeliveryPort;
import com.jjx.customer.platform.delivery.DeliveryPortFactory;
import com.jjx.customer.platform.document.DocumentCatalog;
import com.jjx.customer.platform.intent.IntentResult;
import com.jjx.customer.platform.knowledge.answer.KnowledgeAnswerService;
import com.jjx.customer.platform.knowledge.retrieval.CacheKeys;
import com.jjx.customer.platform.knowledge.retrieval.DocumentVersionStamp;
import com.jjx.customer.platform.knowledge.retrieval.RetrievalBudget;
import com.jjx.customer.platform.knowledge.retrieval.RetrievedChunk;
import com.jjx.customer.platform.knowledge.retrieval.SearchContext;
import com.jjx.customer.platform.knowledge.retrieval.SemanticAnswerCache;
import com.jjx.customer.platform.routing.RouteContext;
import com.jjx.customer.platform.routing.RouteDecision;
import com.jjx.customer.platform.routing.RouteRegistry;
import io.opentelemetry.api.trace.Span;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 对话编排器（ChatOrchestrator）：一次对话请求的决策中枢。
 *
 * <p>本类属于 <b>business（业务/调度层）</b>，只做"怎么决策、交给谁执行、产出什么"，不含任何传输与持久化细节：
 * 交付走 {@link DeliveryPortFactory} / {@link DeliveryPort}（SPI，实现在 platform-delivery），
 * 会话上下文走 {@link ConversationStore}（SPI，实现在 platform-delivery），
 * RAG 能力走 platform-knowledge（检索 + 生成），agent/workflow 执行走平台引擎（agent-framework）。</p>
 *
 * <p>编排步骤：</p>
 * <ol>
 *   <li><b>会话恢复</b> — 有活动追问会话时，本轮消息按会话归属合并槽位继续</li>
 *   <li><b>归一化 + 规则短路</b> — 规则表第一次求值（意图未就绪：traceId 类正则规则可命中）</li>
 *   <li><b>显式范式</b> — 用户显式选择 workflow 型 agent（如运维诊断）时跳过意图分类</li>
 *   <li><b>指代悬空</b> — 「这篇文档」类指代在会话里找不到先行对象时反问澄清</li>
 *   <li><b>意图分类 + 规则路由</b> — 规则表第二次求值（意图域规则按 domain + 置信度命中）</li>
 *   <li><b>短路分支</b> — 闲聊直答 / 诊断 agent 支路（Outcome 分派：追问、直答、升级）</li>
 *   <li><b>RAG 主线</b> — 历史加载 → LLM 改写 → agent+workflow 检索 → 两级答案缓存 → 上下文装配 → 流式生成</li>
 * </ol>
 *
 * @param <S> 传输载体类型（如 {@code SseEmitter}）：对编排层不透明，只透传给交付端口
 */
@Service
@RequiredArgsConstructor
public class ChatOrchestrator<S> {

    private static final TelemetryLogger log = TelemetryLogger.of(ChatOrchestrator.class);

    private final DeliveryPortFactory<S> deliveryPortFactory;
    private final ConversationStore conversationStore;
    private final IntentClassifier intentClassifier;
    private final FrameworkKnowledgeRunner frameworkKnowledgeRunner;
    private final FrameworkOpsRunner frameworkOpsRunner;
    private final PromptFingerprintResolver promptFingerprintResolver;
    private final RouteRegistry routeRegistry;
    private final AgentProperties agentProperties;
    private final ChatProperties chatProperties;
    private final PromptStore promptStore;
    private final QueryRewriter queryRewriter;
    private final SessionStore sessionStore;
    private final AgentTraceService agentTraceService;
    private final KnowledgeAnswerService knowledgeAnswerService;
    private final TelemetryTemplate obsTemplate;
    private final DocumentCatalog documentCatalog;
    private final RagContextAssembler ragContextAssembler;
    private final DegradeGuard degradeGuard;
    private final CacheStore cacheStore;
    private final CacheProperties cacheProperties;
    private final ObjectMapper objectMapper;
    private final DocumentVersionStamp docverStamp;
    private final FrequencyTracker frequencyTracker;
    private final CacheFrequencyPolicy frequencyPolicy;
    private final SemanticAnswerCache semanticAnswerCache;

    /** 流式回答落库/收尾专用（虚拟线程）：complete 回调跑在 reactor 事件循环上，阻塞 JDBC 必须移出 */
    @Qualifier("chatPersistExecutor")
    private final ExecutorService chatPersistExecutor;

    /**
     * 执行一次对话编排（注册「停止生成」取消句柄：初始=关交付流，进入流式回答段后升级为
     * dispose LLM 流 + 部分回答落库的完全体；POST /chat/cancel 按会话触发）。
     *
     * @param question       用户问题
     * @param conversationId 会话 ID
     * @param agent          agent 范式（检索链内编排用；兼容旧客户端/评测，不参与意图路由）
     * @param agentChoice    用户显式选择的范式（新前端用户主动选择时才发送；参与意图路由）
     * @param sink           传输载体（交给交付端口）
     */
    public void execute(String question, String conversationId, String agent, String agentChoice, S sink) {
        DeliveryPort entryPort = deliveryPortFactory.begin(sink, conversationId, question, null, null, null);
        entryPort.beginRequest(conversationId);
        try {
            doExecute(question, conversationId, agent, agentChoice, sink);
        } finally {
            entryPort.endRequest(conversationId);
        }
    }

    /** 编排主体（决策链）。 */
    private void doExecute(String question, String conversationId, String agent, String agentChoice, S sink) {
        conversationStore.ensureConversation(conversationId, question);
        conversationStore.appendUserMessage(conversationId, question);
        long startTime = System.currentTimeMillis();
        String otelTraceId = currentTraceId();

        // ========== 0. 活动会话恢复（优先级最高，先于归一化/意图分类——否则"prod 环境，接口是 xxx"
        // 这类补槽消息会被误判成闲聊/悬空指代）==========
        AgentSessionState activeSession = sessionStore.findActive(conversationId).orElse(null);
        boolean resumed = activeSession != null && sessionStore.claim(conversationId);
        AgentSessionState resumedState = resumed ? activeSession : null;
        if (resumed) {
            log.info("[对话编排] 恢复追问会话: stage={}, slots={}", activeSession.stage(), activeSession.slots());
        }

        String ruleNormalized = obsTemplate.step("rag.query.normalize", question, () -> QueryNormalizer.normalize(question));
        if (ruleNormalized.isBlank()) {
            ruleNormalized = question == null ? "" : question.trim();
        }

        // ========== 1. 路由规则表第一次求值（意图分类前，intent=null：正则短路类规则可命中，
        // 如消息含 traceId 直接进运维诊断——省掉 rewrite+classify 两次 LLM 往返）。
        // 恢复路径不在此短路（traceId 由 ops agent 抽取合并进槽位）==========
        String traceId = TraceIdExtractor.extract(question);
        Optional<RouteDecision> shortCircuit = routeRegistry.evaluate(new RouteContext(
                question, ruleNormalized, null, traceId, activeSession == null ? null : activeSession.agentId(), agentChoice));
        if (shortCircuit.isPresent() && !resumed) {
            RouteDecision d = shortCircuit.get();
            log.info("[对话编排] 规则短路直接进 {} 分支: traceId={}", d.agentType(), traceId);
            runAgentBranch(d.agentType(),
                    question, conversationId, sink, otelTraceId, d.prefillSlots(), null);
            return;
        }
        if (resumed) {
            // 恢复：本轮消息是补充信息，按会话归属路由给创建它的 agent 合并槽位继续
            // （会话 agentType 空白/未知回 ops——存量 sa_agent_session 全是 ops 的旧数据兼容）
            runAgentBranch(requireResumeTarget(resumedState),
                    question, conversationId, sink, otelTraceId, Map.of(), resumedState.slots());
            return;
        }

        // ========== 1.5 显式范式选择的强制路由（优先级：用户选择 > 意图识别 > 默认知识检索）==========
        // 只认 agentChoice（新前端用户主动选择时才发送）——agent 参数兼容旧客户端/评测默认值
        // （旧 JS 默认带 agent=knowledge），不能当显式选择，否则意图路由被无声旁路。
        // 选了 workflow 型范式（如运维诊断）→ 直接进该 agent，跳过意图分类——否则「帮我看看这个问题」
        // 类短句可能被误判成闲聊短路，用户的显式选择被意图识别覆盖；
        // 检索型范式（knowledge/react_loop）仍走下方 RAG 链的 agent 编排（产物是 chunks+流式答案）。
        if (OpsDiagnoseFrameworkAgent.ID.equalsIgnoreCase(agentChoice)) {
            log.info("[对话编排] 用户显式选择 {}，跳过意图分类直接进该 agent", agentChoice);
            runAgentBranch(OpsDiagnoseFrameworkAgent.ID, question, conversationId, sink, otelTraceId, Map.of(), null);
            return;
        }

        // ========== 2. 指代悬空检测（纯正则 + 一次轻查询）：「这篇文档」类指代在会话里找不到先行对象时
        // 反问澄清，而不是把"这篇"静默解释成检索第一名（检索到哪篇就总结哪篇，用户易被误导） ==========
        if (isDanglingDocReference(question, conversationId)) {
            log.info("[对话编排] 指代悬空（会话无文档语境），反问澄清: question=\"{}\"", question);
            askForDocClarification(conversationId, sink, otelTraceId);
            return;
        }

        // ========== 3. 意图分类（吃规则归一化结果；对改写不敏感）==========
        IntentResult intent = intentClassifier.classify(ruleNormalized);
        log.info("[对话编排] 意图分类结果: domain={}, confidence={}, needsRetrieval={}, reason={}",
                intent.getDomain(), intent.getConfidence(), intent.needsRetrieval(), intent.getReason());

        // ========== 3.5 路由规则表第二次求值（intent 就绪：意图域规则按 domain+置信度判断，
        // 低置信度不命中落回知识检索；显式选择 knowledge 的压制逻辑在 ops 域规则内）==========
        Optional<RouteDecision> byIntent = routeRegistry.evaluate(new RouteContext(
                question, ruleNormalized, intent, traceId, activeSession == null ? null : activeSession.agentId(), agentChoice));
        if (byIntent.isPresent()) {
            RouteDecision d = byIntent.get();
            log.info("[对话编排] 意图路由命中 {} 分支（domain={}, conf={}）",
                    d.agentType(), intent.getDomain(), intent.getConfidence());
            runAgentBranch(d.agentType(),
                    question, conversationId, sink, otelTraceId, d.prefillSlots(), null);
            return;
        }

        // ========== 4. 非查询类短路由（闲聊短路，不做 LLM 改写）==========
        if (!intent.needsRetrieval()) {
            log.info("[对话编排] 非查询意图，走闲聊回复");
            handleNonQuery(question, conversationId, sink, otelTraceId);
            return;
        }

        // ========== 5. 需要检索：才做历史加载 + LLM 改写 ==========
        String historyForRewrite = conversationStore.historyContext(conversationId);
        String query = queryRewriter.rewrite(ruleNormalized, historyForRewrite);
        log.info("[对话编排] 会话ID={}, 原始={}, 规则归一化={}, LLM改写={}",
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
                        "intent", intent.getDomain(),
                        "needsWebSearch", intent.isNeedsWebSearch()))
                .build();

        log.info("[对话编排] 开始检索(agent={}): topK={}, 阈值={}, recallBudget={}, candidateLimit={}, contextTopK={}",
                agentProperties.paradigmCode(),
                chatProperties.getTopK(), chatProperties.getSimilarityThreshold(),
                chatProperties.getRecallBudget(), chatProperties.getCandidateLimit(), chatProperties.getContextTopK());

        // ===== 检索主线：知识问答 = 框架 Agent + Workflow（检索=extension tool，执行权在框架引擎）=====
        // ?agent= 指定范式轴（knowledge / react_loop）；ops 型在更早分支已进 agent 支路。
        if (OpsDiagnoseFrameworkAgent.ID.equalsIgnoreCase(agent)) {
            runAgentBranch(OpsDiagnoseFrameworkAgent.ID, question, conversationId, sink, otelTraceId, Map.of(), null);
            return;
        }
        String paradigm = StringUtils.hasText(agent) ? agent : KnowledgeFrameworkAgent.ID;

        // ========== 6.5 答案缓存两级查询（exact → 语义）：同问题或同义问题直接重放，省检索+生成整链。
        // 到达此处的前置条件（needsRetrieval && !needsDiagnose && !resumed && traceId==null）由前面各 return 保证。
        // 频率准入：窗口内首次出现的问题不写 exact 缓存（长尾防污染），出现满阈值才写 ==========
        // exact key 用规则归一化问题（读写同源，吃掉空格/标点/全半角变体）
        String answerCacheKey = answerCacheKey(ruleNormalized, searchCtx, paradigm);
        if (answerCacheKey != null) {
            long freq = frequencyTracker.observe(answerCacheKey);
            if (tryReplayCachedAnswer(answerCacheKey, freq, conversationId, sink,
                    otelTraceId, paradigm)) {
                return;
            }
            if (!frequencyPolicy.admits(freq, cacheProperties.getAnswer().getAdmissionThreshold(), false)) {
                answerCacheKey = null; // 本轮不写 exact：streamRagResponse 端按 null 跳过写回
            }
        }
        // 语义层：同义不同字面的问题（exact 的频率计数被变体分散，正是语义缓存的靶子）
        if (cacheProperties.isEnabled() && cacheProperties.getSemantic().isEnabled()) {
            Optional<SemanticAnswerCache.SemanticHit> hit = semanticAnswerCache.lookup(
                    ruleNormalized, paradigm, promptFingerprintResolver.promptHash(paradigm));
            if (hit.isPresent() && replayAnswer(hit.get().answer(), hit.get().citationsJson(),
                    conversationId, sink, otelTraceId, paradigm)) {
                // 语义命中回写 exact：本问下次免 embed 直达（exact 快、语义准，两层各取所长；
                // messageId 一并存入，exact 命中时可联动语义记录计数）
                if (answerCacheKey != null) {
                    try {
                        cacheStore.put(answerCacheKey, objectMapper.writeValueAsString(new CachedAnswer(
                                        hit.get().answer(), hit.get().citationsJson(), paradigm,
                                        hit.get().messageId())),
                                cacheProperties.getAnswer().getTtl(), "answer");
                    } catch (Exception cacheEx) {
                        log.warn("[对话编排] 语义命中回写 exact 失败（忽略）: {}", cacheEx.getMessage());
                    }
                }
                return;
            }
        }

        // ===== 执行：框架主线（knowledge / react_loop 都是框架流程，差异只在节点形态）=====
        FrameworkKnowledgeRunner.KnowledgeAnswer knowledgeAnswer =
                frameworkKnowledgeRunner.retrieve(question, searchCtx, paradigm);
        List<RetrievedChunk> chunks = knowledgeAnswer.chunks();
        TraceView trace = knowledgeAnswer.trace();
        log.info("[对话编排] 框架主线({}) 完成: 最终块={}条, 检索耗时={}ms, llm调用={}次",
                paradigm, chunks.size(), System.currentTimeMillis() - tRetrieve, trace.getLlmCallCount());

        // 发送执行轨迹（SSE trace 事件，前端对照面板用；一次性，在流式回答前发出）
        deliveryPortFactory.begin(sink, conversationId, question, otelTraceId, paradigm, trace)
                .emitTrace(conversationId, trace);

        // ========== 7. 空检索处理 ==========
        if (chunks.isEmpty()) {
            log.warn("[对话编排] 检索结果为空，返回降级提示");
            Long emptyMsgId = handleRetrievalEmpty(question, conversationId, sink, otelTraceId);
            agentTraceService.record(conversationId, emptyMsgId, paradigm, question, trace, otelTraceId);
            return;
        }

        // ========== 8. 构建上下文 + 流式回答 ==========
        log.info("[对话编排] 开始流式回答, 上下文共 {} 条", chunks.size());
        streamRagResponse(question, conversationId, chunks, sink, startTime,
                paradigm, trace, otelTraceId, answerCacheKey, ruleNormalized);
    }

    // ==================== 精确答案缓存 ====================

    /** 答案缓存载荷（Jackson record 构造器绑定；messageId 用于 exact 命中时联动语义记录计数，旧载荷无此字段反序列化为 null）。 */
    record CachedAnswer(String answer, String citationsJson, String paradigm, Long messageId) {
    }

    /** 缓存 key；层未启用或 docver 不可用（Redis 挂）返回 null = 本轮不读不写。
     *  key 含 prompt 内容指纹（改任一层 prompt ⇒ 自动失效，F6 验收口径）。 */
    private String answerCacheKey(String question, SearchContext searchCtx, String paradigm) {
        if (!cacheProperties.isEnabled() || !cacheProperties.getAnswer().isEnabled()) {
            return null;
        }
        String collectionKey = searchCtx.getCollectionId() == null
                ? DocumentVersionStamp.ALL_COLLECTIONS : String.valueOf(searchCtx.getCollectionId());
        long version = docverStamp.current(collectionKey);
        if (version < 0) {
            return null;
        }
        return CacheKeys.answerKey(question, paradigm, version,
                promptFingerprintResolver.promptHash(paradigm));
    }

    /**
     * exact 命中重放：解析载荷 + 重放 + 热度延长。
     *
     * @return true = 已重放并收尾，调用方直接 return
     */
    private boolean tryReplayCachedAnswer(String cacheKey, long freq, String conversationId, S sink,
                                          String otelTraceId, String paradigm) {
        String json = cacheStore.get(cacheKey, "answer").orElse(null);
        if (json == null) {
            return false;
        }
        CachedAnswer ca;
        try {
            ca = objectMapper.readValue(json, CachedAnswer.class);
        } catch (Exception e) {
            log.warn("[对话编排] 答案缓存坏载荷当 miss: {}", e.getMessage());
            return false;
        }
        if (ca == null || !StringUtils.hasText(ca.answer())) {
            return false;
        }
        replayAnswer(ca.answer(), ca.citationsJson(), conversationId, sink, otelTraceId, paradigm);
        // 热度延长：重放成功且窗口计数达档位时延长 TTL
        java.time.Duration scaled = frequencyPolicy.scaledTtl(freq, cacheProperties.getAnswer().getTtl());
        if (scaled != null && !scaled.equals(cacheProperties.getAnswer().getTtl())) {
            cacheStore.touch(cacheKey, scaled, "answer");
        }
        // 命中口径联动：exact 层命中也让对应语义记录的命中次数 +1（看板「命中」不因服务层不同而冻结）
        semanticAnswerCache.bumpByMessageId(ca.messageId());
        log.info("[对话编排] 答案缓存命中重放(exact): 会话ID={}, 范式={}", conversationId, paradigm);
        return true;
    }

    /**
     * 重放缓存答案（exact/语义共用，同步，镜像 streamDirectAnswer 模式——请求线程直接落库）：
     * citations → 分段 message → 落库（带 citations）→ meta → complete。
     * 无 trace 事件、无 agentTraceService.record（by design：没有 agent 执行就没有轨迹）。
     */
    private boolean replayAnswer(String answer, String citationsJson, String conversationId,
                                 S sink, String otelTraceId, String paradigm) {
        deliveryPortFactory.begin(sink, conversationId, null, otelTraceId, paradigm, null)
                .emitCachedReplay(conversationId, answer, citationsJson, paradigm);
        return true;
    }

    // ==================== agent 直执行分支（workflow 型） ====================

    /** 会话恢复的目标 agent：按会话归属路由；agentId 空白/未知回 ops（存量会话全是 ops 的旧数据兼容）。 */
    private String requireResumeTarget(AgentSessionState state) {
        return StringUtils.hasText(state.agentId())
                ? state.agentId() : OpsDiagnoseFrameworkAgent.ID;
    }

    /**
     * workflow 型 agent 统一入口（构建 {@code AgentTask}（含恢复会话与预填槽位）→ 执行 → Outcome 分派；
     * REST 诊断入口为 DiagnoseController 薄壳，同一 agent 单一实现）。
     * 目标 agent 由调用方解析（traceId 短路 / 会话恢复 / 显式选择 / 意图路由各自按语义路由）。
     *
     * @param agentType    目标 agent 类型（当前为 ops_diagnose）
     * @param prefillSlots 正则预填槽位（如消息里提取到的 traceId），可空
     * @param resumedSlots 恢复的会话已确认槽位（追问补充轮），可空
     */
    private void runAgentBranch(String agentType, String question, String conversationId, S sink,
                                String otelTraceId,
                                Map<String, String> prefillSlots,
                                Map<String, String> resumedSlots) {
        // 槽位合并：会话已确认值 + 本轮预填（trace_id 等正则提取结果；只保留 ops 目录内槽位）
        Map<String, String> mergedSlots = new LinkedHashMap<>();
        if (resumedSlots != null) {
            mergedSlots.putAll(resumedSlots);
        }
        mergedSlots.putAll(OpsSlotSpecs.sanitized(prefillSlots));

        // 降级闸：Redis 断路器 OPEN 期间收紧 ops 诊断并发（缓存保护消失时给下游留活口）
        DegradeGuard.Lease opsLease = degradeGuard.tryAcquire().orElse(null);
        if (opsLease == null) {
            rejectDegraded(conversationId, sink, otelTraceId);
            return;
        }

        deliveryPortFactory.begin(sink, conversationId, question, otelTraceId, null, null)
                .emitDelta(conversationId, "正在排查，请稍候…\n\n");

        long t = System.currentTimeMillis();
        // 框架主线：ops 三阶段 workflow（查日志 → 检索/生成或纠正 → 校验收尾），执行权在框架引擎
        FrameworkOpsRunner.OpsAnswer answer;
        try {
            answer = frameworkOpsRunner.run(question, mergedSlots, conversationId);
        } finally {
            opsLease.close();
        }
        TraceView trace = answer.trace();

        log.info("[对话编排] agent 分支完成: type={}, kind={}, 耗时={}ms, llm调用={}次",
                agentType, answer.kind(), System.currentTimeMillis() - t,
                trace != null ? trace.getLlmCallCount() : 0);

        // trace 事件（回答/追问前发出，前端对照面板复用）
        if (trace != null) {
            deliveryPortFactory.begin(sink, conversationId, question, otelTraceId, agentType, trace)
                    .emitTrace(conversationId, trace);
        }

        switch (answer.kind()) {
            case CLARIFY -> handleClarify(answer.text(), question, conversationId, sink, otelTraceId, agentType, trace);
            case DIRECT -> streamDirectAnswer(answer.text(), question, conversationId, sink, otelTraceId, agentType, trace);
            case ESCALATE -> handleEscalate(answer.text(), question, conversationId, sink, otelTraceId, agentType, trace);
            case WITH_CONTEXT -> streamDirectAnswer(answer.text(), question, conversationId, sink, otelTraceId, agentType, trace);
        }
    }

    /** 追问中断：clarify 事件（结构化缺失槽位）→ assistant 消息落库 → 会话状态 AWAITING_USER → meta → 结束。 */
    private void handleClarify(String text, String question, String conversationId,
                               S sink, String otelTraceId, String paradigm, TraceView trace) {
        deliveryPortFactory.begin(sink, conversationId, question, otelTraceId, paradigm, trace)
                .emitClarify(conversationId, text, paradigm, null);
    }

    /** 直答：诊断结论/正确报文不经 RAG 再生成，分段流式输出 + 落库 + meta。 */
    private void streamDirectAnswer(String text, String question, String conversationId,
                                    S sink, String otelTraceId, String paradigm, TraceView trace) {
        deliveryPortFactory.begin(sink, conversationId, question, otelTraceId, paradigm, trace)
                .emitDirect(conversationId, text, paradigm);
    }

    /** replan 升级：说明卡住原因并转追问文案（会话已由 agent 保存为 AWAITING_USER）。 */
    private void handleEscalate(String text, String question, String conversationId,
                                S sink, String otelTraceId, String paradigm, TraceView trace) {
        deliveryPortFactory.begin(sink, conversationId, question, otelTraceId, paradigm, trace)
                .emitEscalate(conversationId, text, paradigm, null);
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
        long priorUserMsgs = conversationStore.userMessageCount(conversationId);
        if (priorUserMsgs <= 1) {
            return true;
        }
        // 有历史：拼最近 user 消息文本，与库内文档名比对（含去扩展名主干，用户提名字常不带后缀）
        String recentUserText = conversationStore.recentUserText(conversationId, 10);
        List<String> docNames = documentCatalog.allDocumentNames();
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
    private void askForDocClarification(String conversationId, S sink, String otelTraceId) {
        String ask = "你的问题指向某篇具体的文档，但我们当前的对话里还没有确定是哪一篇。\n\n"
                + "可以带上文档名提问，比如「总结《增值税发票冲红流程.pdf》」；"
                + "也可以描述得更具体一些（比如文档的主题、里面提到的内容），我来帮你定位。";
        deliveryPortFactory.begin(sink, conversationId, null, otelTraceId, null, null)
                .emitNotice(conversationId, ask, otelTraceId);
    }

    /**
     * 处理问候/闲聊（不检索，直接回答）。
     */
    private void handleNonQuery(String question, String conversationId, S sink, String otelTraceId) {
        log.info("[对话编排] 走闲聊回复: question=\"{}\"", question);

        DegradeGuard.Lease lease = degradeGuard.tryAcquire().orElse(null);
        if (lease == null) {
            rejectDegraded(conversationId, sink, otelTraceId);
            return;
        }

        StringBuilder fullAnswer = new StringBuilder();
        // 流式回调线程（reactor-netty）的 ambient HOLDER 常未恢复，subscribe 前于业务线程捕获 output sink
        Consumer<Object> outputSink = log.conversationSink();
        // 停止/断连时部分回答落库（与完成回调共享防双写标志）
        AtomicBoolean persisted = new AtomicBoolean();
        Runnable persistPartial = () -> {
            if (persisted.compareAndSet(false, true)) {
                String partial = fullAnswer.toString();
                if (!partial.isBlank()) {
                    chatPersistExecutor.execute(() ->
                            deliveryPortFactory.begin(sink, conversationId, question, otelTraceId, null, null)
                                    .persistAnswerOnly(conversationId, partial));
                }
            }
        };
        // 流式交付交给交付层：逐 token 送达 + 异常/完成收口
        DeliveryPort.StreamSpec spec = new DeliveryPort.StreamSpec(conversationId, question, null, otelTraceId,
                null, fullAnswer, outputSink,
                answer -> {
                    if (!persisted.compareAndSet(false, true)) {
                        return null;
                    }
                    try {
                        return CompletableFuture.supplyAsync(
                                        () -> deliveryPortFactory
                                                .begin(sink, conversationId, question, otelTraceId, null, null)
                                                .persistAnswerOnly(conversationId, answer),
                                        chatPersistExecutor)
                                .get(5, TimeUnit.SECONDS);
                    } catch (Exception persistEx) {
                        log.warn("[对话编排] 闲聊落库失败", persistEx);
                        return null;
                    }
                },
                persistPartial, null,
                DeliveryPort.StreamFailureMode.COMPLETE,
                null,
                lease::close);
        deliveryPortFactory.beginStream(sink, conversationId, question, otelTraceId, null, null)
                .emitStream(conversationId, knowledgeAnswerService.chitchat(question, conversationId), spec);
    }

    /**
     * 处理检索无结果的情况——直接回复降级消息，不走 LLM。
     *
     * @return 降级 assistant 消息 id（供调用处关联 agent trace 落库；内容为空时为 null）
     */
    private Long handleRetrievalEmpty(String question, String conversationId, S sink, String otelTraceId) {
        log.info("[对话编排] 检索为空，直接返回降级提示: 会话ID={}", conversationId);
        String fallbackMsg = StringUtils.hasText(chatProperties.getEmptyRetrievalMsg())
                ? chatProperties.getEmptyRetrievalMsg()
                : promptStore.raw("chat/pipeline/empty-retrieval");
        return deliveryPortFactory.begin(sink, conversationId, question, otelTraceId, null, null)
                .emitNotice(conversationId, fallbackMsg, otelTraceId);
    }

    /**
     * 装配 RAG 上下文并流式回答。
     *
     * <p>系统提示词（回答规则）由 ragChatClient 的 defaultSystem 承载
     * （prompts/chat/pipeline/rag-answer-system.md），此处只把检索资料与用户问题组装进
     * user message——避免把每次都变的资料拼进 system 而破坏 prompt cache，也让规则稳定可缓存。</p>
     *
     * <p>生成能力在 {@link KnowledgeAnswerService#answer}（{@code @TelemetryStep} 自动埋点），
     * 上下文组装在 {@link RagContextAssembler}；本方法只做装配与端口调用。</p>
     */
    private void streamRagResponse(String question, String conversationId,
                                   List<RetrievedChunk> chunks, S sink, long t0,
                                   String paradigm, TraceView trace, String otelTraceId,
                                   String answerCacheKey, String normalizedQuestion) {
        // 降级闸：Redis 断路器 OPEN 期间收紧 LLM 流式并发（缓存命中/重放路径不经过此处，天然不受限）
        DegradeGuard.Lease lease = degradeGuard.tryAcquire().orElse(null);
        if (lease == null) {
            rejectDegraded(conversationId, sink, otelTraceId);
            return;
        }

        RagContextAssembler.RagContext ragContext = ragContextAssembler.buildContextText(chunks);
        log.info("[对话编排] 给 LLM 的上下文: {}块{}文档 {}字符",
                chunks.size(), ragContext.docCount(), ragContext.text().length());

        // 引用映射先行下发（流式开始前，与 trace 事件同模式）：正文里的 [N] 角标靠它渲染
        String citationsJson = toJsonOrNull(ragContext.citations());

        DeliveryPort port = deliveryPortFactory.beginStream(sink, conversationId, question, otelTraceId, paradigm, trace);
        port.emitCitations(conversationId, citationsJson);

        StringBuilder fullAnswer = new StringBuilder();
        // 流式回调线程（reactor-netty）的 ambient HOLDER 常未恢复，subscribe 前于业务线程捕获 output sink
        Consumer<Object> outputSink = log.conversationSink();
        // 停止/断连时部分回答落库（带引用 + 轨迹；与完成回调共享防双写标志）
        AtomicBoolean persisted = new AtomicBoolean();
        Runnable persistPartial = () -> {
            if (persisted.compareAndSet(false, true)) {
                String partial = fullAnswer.toString();
                if (partial.isBlank()) {
                    return;
                }
                chatPersistExecutor.execute(() -> {
                    deliveryPortFactory.begin(sink, conversationId, question, otelTraceId, paradigm, trace)
                            .persistAnswer(conversationId, question, paradigm, partial, citationsJson,
                                    trace, otelTraceId);
                });
            }
        };
        // 流式交付交给交付层：逐 token 送达 + 完成/失败收口；落库与缓存写回仍由编排层提供回调（行为不变）
        DeliveryPort.StreamSpec spec = new DeliveryPort.StreamSpec(conversationId, question, paradigm, otelTraceId,
                trace, fullAnswer, outputSink,
                answer -> {
                    if (!persisted.compareAndSet(false, true)) {
                        return null;
                    }
                    try {
                        return CompletableFuture.supplyAsync(
                                        () -> {
                                            Long msgId = deliveryPortFactory
                                                    .begin(sink, conversationId, question, otelTraceId,
                                                            paradigm, trace)
                                                    .persistAnswer(conversationId, question, paradigm, answer,
                                                            citationsJson, trace, otelTraceId);
                                            log.info("========== [对话编排] 完成 ========== 会话ID={}, 耗时={}ms",
                                                    conversationId, System.currentTimeMillis() - t0);
                                            if (answerCacheKey != null && StringUtils.hasText(answer)) {
                                                try {
                                                    cacheStore.put(answerCacheKey,
                                                            objectMapper.writeValueAsString(new CachedAnswer(
                                                                    answer, citationsJson, paradigm, msgId)),
                                                            cacheProperties.getAnswer().getTtl(), "answer");
                                                } catch (Exception cacheEx) {
                                                    log.warn("[对话编排] 答案缓存写入失败（忽略）: {}", cacheEx.getMessage());
                                                }
                                            }
                                            semanticAnswerCache.store(normalizedQuestion, answer, citationsJson,
                                                    paradigm, promptFingerprintResolver.promptHash(paradigm), msgId);
                                            return msgId;
                                        },
                                        chatPersistExecutor)
                                .get(5, TimeUnit.SECONDS);
                    } catch (Exception persistEx) {
                        log.warn("[对话编排] 保存助手消息/轨迹/缓存失败", persistEx);
                        return null;
                    }
                },
                persistPartial, null,
                DeliveryPort.StreamFailureMode.SIGNAL_ERROR,
                null,
                lease::close);
        // systemPrompt=null：P2 快照装配未接线，服务侧回退 classpath 基线
        port.emitStream(conversationId, knowledgeAnswerService.answer(question, ragContext.text(), null), spec);
    }

    /** 降级闸拒绝：礼貌提示 + 落库 + meta + complete（不跑 LLM，宁可拒绝不排队）。 */
    private void rejectDegraded(String conversationId, S sink, String otelTraceId) {
        deliveryPortFactory.begin(sink, conversationId, null, otelTraceId, null, null)
                .emitDegraded(conversationId, otelTraceId);
    }

    /** 引用映射序列化（失败返回 null：引用溯源是增强功能，绝不能阻断回答主链路）。 */
    private String toJsonOrNull(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("[对话编排] 序列化失败（忽略）: {}", e.getMessage());
            return null;
        }
    }

    /** 当前请求的 OTel traceId（{@code rag.chat} 根 span）；仅业务线程内有效，无有效 span 时返回 null。 */
    private static String currentTraceId() {
        var ctx = Span.current().getSpanContext();
        return ctx.isValid() ? ctx.getTraceId() : null;
    }
}
