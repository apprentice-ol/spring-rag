package com.jjx.customer.platform.business.orchestration;

import com.jjx.ai.llmobservability.observation.TelemetryTemplate;
import com.jjx.ai.llmobservability.observation.logging.TelemetryLogger;
import com.jjx.customer.platform.business.engine.AgentCatalog;
import com.jjx.customer.platform.business.knowledge.KnowledgeRunner;
import com.jjx.customer.platform.business.knowledge.RewritePolicy;
import com.jjx.customer.platform.business.session.AgentSessionState;
import com.jjx.customer.platform.business.trace.AgentTraceService;
import com.jjx.customer.platform.business.trace.model.TraceView;
import com.jjx.customer.platform.common.util.QueryNormalizer;
import com.jjx.customer.platform.config.prompt.PromptStore;
import com.jjx.customer.platform.config.properties.AgentProperties;
import com.jjx.customer.platform.config.properties.ChatProperties;
import com.jjx.customer.platform.conversation.ConversationStore;
import com.jjx.customer.platform.delivery.DeliveryPort;
import com.jjx.customer.platform.delivery.DeliveryPortFactory;
import com.jjx.customer.platform.knowledge.retrieval.RetrievalBudget;
import com.jjx.customer.platform.knowledge.retrieval.RetrievedChunk;
import com.jjx.customer.platform.knowledge.retrieval.SearchContext;
import com.jjx.customer.platform.routing.RouteDecision;
import io.opentelemetry.api.trace.Span;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 对话编排器（ChatOrchestrator）：一次对话请求的决策中枢。
 *
 * <p>本类属于 <b>business（业务/调度层）</b>，只做"怎么决策、交给谁执行、产出什么"，不含任何传输与持久化细节：
 * 交付走 {@link DeliveryPortFactory} / {@link DeliveryPort}（SPI，实现在 platform-delivery），
 * 会话上下文走 {@link ConversationStore}（SPI，实现在 platform-delivery），
 * RAG 能力走 platform-knowledge（检索 + 生成），agent/workflow 执行走平台引擎（agent-framework）。</p>
 *
 * <p>编排步骤（决策顺序冻结在 {@link #doExecute}；各步的实现体在协作类里）：</p>
 * <ol>
 *   <li><b>会话恢复</b> — {@link ResumeCoordinator}：有活动追问会话时，本轮消息按会话归属合并槽位继续</li>
 *   <li><b>归一化 + 规则短路</b> — {@link RouteEvaluator}：规则表第一次求值（意图未就绪：traceId 类正则规则可命中）</li>
 *   <li><b>显式范式</b> — 用户显式选择 workflow 型 agent（如运维诊断）时跳过意图分类</li>
 *   <li><b>指代悬空</b> — {@link DocReferenceClarifier}：「这篇文档」类指代在会话里找不到先行对象时反问澄清</li>
 *   <li><b>意图分类 + 规则路由</b> — 规则表第二次求值（意图域规则按 domain + 置信度命中）</li>
 *   <li><b>短路分支</b> — {@link AgentBranchDispatcher}：闲聊直答 / 诊断 agent 支路（Outcome 分派：追问、直答、升级）</li>
 *   <li><b>答案缓存</b> — {@link AnswerCacheCoordinator}：两级查询（exact → 语义）与重放</li>
 *   <li><b>RAG 主线</b> — 历史加载 → LLM 改写 → agent+workflow 检索 → 两级答案缓存 → {@link RagAnswerStreamer} 流式生成</li>
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
    private final KnowledgeRunner knowledgeRunner;
    private final AgentProperties agentProperties;
    private final ChatProperties chatProperties;
    private final PromptStore promptStore;
    private final AgentTraceService agentTraceService;
    private final TelemetryTemplate obsTemplate;
    private final ResumeCoordinator resumeCoordinator;
    private final RouteEvaluator routeEvaluator;
    private final DocReferenceClarifier<S> docReferenceClarifier;
    private final AgentBranchDispatcher<S> agentBranchDispatcher;
    private final AnswerCacheCoordinator<S> answerCacheCoordinator;
    private final RagAnswerStreamer<S> ragAnswerStreamer;
    private final ChitchatResponder<S> chitchatResponder;

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
        ResumeCoordinator.ResumeDecision resume = resumeCoordinator.findResumable(conversationId);
        AgentSessionState activeSession = resume.activeSession();
        boolean resumed = resume.resumed();

        String ruleNormalized = obsTemplate.step("rag.query.normalize", question, () -> QueryNormalizer.normalize(question));
        if (ruleNormalized.isBlank()) {
            ruleNormalized = question == null ? "" : question.trim();
        }

        // ========== 1. 路由规则表第一次求值（意图分类前，intent=null：正则短路类规则可命中，
        // 如消息含 traceId 直接进运维诊断——省掉 rewrite+classify 两次 LLM 往返）。
        // 恢复路径不在此短路（traceId 由 ops agent 抽取合并进槽位）==========
        RouteEvaluator.FirstPass firstPass = routeEvaluator.evaluateFirstPass(
                question, ruleNormalized, activeSession, agentChoice);
        String traceId = firstPass.traceId();
        if (firstPass.shortCircuit().isPresent() && !resumed) {
            RouteDecision d = firstPass.shortCircuit().get();
            log.info("[对话编排] 规则短路直接进 {} 分支: traceId={}", d.agentType(), traceId);
            agentBranchDispatcher.runAgentBranch(d.agentType(),
                    question, conversationId, sink, otelTraceId, d.prefillSlots(), null);
            return;
        }
        if (resumed) {
            // 恢复：本轮消息是补充信息，按会话归属路由给创建它的 agent 合并槽位继续
            // （会话 agentType 空白/未知回 ops——存量 sa_agent_session 全是 ops 的旧数据兼容）
            agentBranchDispatcher.runAgentBranch(resumeCoordinator.requireResumeTarget(activeSession),
                    question, conversationId, sink, otelTraceId, Map.of(), activeSession.slots());
            return;
        }

        // ========== 1.5 显式范式选择的强制路由（优先级：用户选择 > 意图识别 > 默认知识检索）==========
        // 只认 agentChoice（新前端用户主动选择时才发送）——agent 参数兼容旧客户端/评测默认值
        // （旧 JS 默认带 agent=knowledge），不能当显式选择，否则意图路由被无声旁路。
        // 选了 workflow 型范式（如运维诊断）→ 直接进该 agent，跳过意图分类——否则「帮我看看这个问题」
        // 类短句可能被误判成闲聊短路，用户的显式选择被意图识别覆盖；
        // 检索型范式（knowledge/react_loop）仍走下方 RAG 链的 agent 编排（产物是 chunks+流式答案）。
        if (AgentCatalog.OPS.id().equalsIgnoreCase(agentChoice)) {
            log.info("[对话编排] 用户显式选择 {}，跳过意图分类直接进该 agent", agentChoice);
            agentBranchDispatcher.runAgentBranch(AgentCatalog.OPS.id(), question, conversationId, sink, otelTraceId, Map.of(), null);
            return;
        }

        // ========== 2. 指代悬空检测（纯正则 + 一次轻查询）：「这篇文档」类指代在会话里找不到先行对象时
        // 反问澄清，而不是把"这篇"静默解释成检索第一名（检索到哪篇就总结哪篇，用户易被误导） ==========
        if (docReferenceClarifier.isDanglingDocReference(question, conversationId)) {
            log.info("[对话编排] 指代悬空（会话无文档语境），反问澄清: question=\"{}\"", question);
            docReferenceClarifier.askForDocClarification(conversationId, sink, otelTraceId);
            return;
        }

        // ========== 3. 检索参数 ==========
        // 查询本身不在这里定：它由图内的 kb_rewrite 节点产出（知识轴是首轮透传/反思轮扩词，
        // 工具参数 ${slots.rewritten_query} 直接取该槽位）。先前这里预置 LLM 改写结果，
        // 结果是图内的重写节点无从回灌——检索永远只吃到第一版查询。
        SearchContext searchCtx = SearchContext.builder()
                .query(question)
                .topK(chatProperties.getTopK())
                .threshold(chatProperties.getSimilarityThreshold())
                .budget(RetrievalBudget.builder()
                        .recallBudget(chatProperties.getRecallBudget())
                        .candidateLimit(chatProperties.getCandidateLimit())
                        .contextTopK(chatProperties.getContextTopK())
                        .build())
                .build();

        log.info("[对话编排] 开始检索(agent={}): topK={}, 阈值={}, recallBudget={}, candidateLimit={}, contextTopK={}",
                agentProperties.paradigmCode(),
                chatProperties.getTopK(), chatProperties.getSimilarityThreshold(),
                chatProperties.getRecallBudget(), chatProperties.getCandidateLimit(), chatProperties.getContextTopK());

        // ===== 检索主线：知识问答 = 框架 Agent + Workflow（检索=extension tool，执行权在框架引擎）=====
        // ?agent= 指定范式轴（knowledge / react_loop）；ops 型在更早分支已进 agent 支路。
        if (AgentCatalog.OPS.id().equalsIgnoreCase(agent)) {
            agentBranchDispatcher.runAgentBranch(AgentCatalog.OPS.id(), question, conversationId, sink, otelTraceId, Map.of(), null);
            return;
        }
        String paradigm = StringUtils.hasText(agent) ? agent : AgentCatalog.KNOWLEDGE.id();

        // ========== 6.5 答案缓存两级查询（exact → 语义）：同问题或同义问题直接重放，省检索+生成整链。
        // 到达此处的前置条件（needsRetrieval && !needsDiagnose && !resumed && traceId==null）由前面各 return 保证。
        // 频率准入：窗口内首次出现的问题不写 exact 缓存（长尾防污染），出现满阈值才写 ==========
        // exact key 用规则归一化问题（读写同源，吃掉空格/标点/全半角变体）
        String answerCacheKey = answerCacheCoordinator.answerCacheKey(ruleNormalized, searchCtx, paradigm);
        if (answerCacheKey != null) {
            long freq = answerCacheCoordinator.observe(answerCacheKey);
            if (answerCacheCoordinator.tryReplayCachedAnswer(answerCacheKey, freq, conversationId, sink,
                    otelTraceId, paradigm)) {
                return;
            }
            if (!answerCacheCoordinator.admits(freq)) {
                answerCacheKey = null; // 本轮不写 exact：streamRagResponse 端按 null 跳过写回
            }
        }
        // 语义层：同义不同字面的问题（exact 的频率计数被变体分散，正是语义缓存的靶子）
        if (answerCacheCoordinator.replaySemanticIfHit(ruleNormalized, paradigm, answerCacheKey,
                conversationId, sink, otelTraceId)) {
            return;
        }

        // ========== 7. 图执行：查询理解链 + 检索（含反思环）==========
        // 归一化 → 意图识别 → 路由 →（闸门）→ 问题重写 → 检索 → 充分性判定 →（不足）重写重查
        // 整条链在图里，所以它进得了执行轨迹；编排层只负责把外部事实（历史、补充、路由上下文）
        // 作为初始槽位喂进去，再从产物槽位把该接手的活读回来。
        String historyForRewrite = conversationStore.historyContext(conversationId);
        long tRetrieve = System.currentTimeMillis();
        KnowledgeRunner.KnowledgeAnswer knowledgeAnswer = knowledgeRunner.retrieve(
                question, searchCtx, paradigm,
                new KnowledgeRunner.RunContext(historyForRewrite, null, traceId,
                        agentChoice, activeSession == null ? null : activeSession.agentId(),
                        RewritePolicy.AUTO, false));
        List<RetrievedChunk> chunks = knowledgeAnswer.chunks();
        TraceView trace = knowledgeAnswer.trace();
        log.info("[对话编排] 框架主线({}) 完成: 最终块={}条, 检索耗时={}ms, 意图={}, 路由={}, llm调用={}次",
                paradigm, chunks.size(), System.currentTimeMillis() - tRetrieve,
                knowledgeAnswer.intent(), knowledgeAnswer.routeTarget(), trace.getLlmCallCount());

        // ========== 8. 分派：按图的判定结果决定这一轮怎么交付 ==========

        // 8.1 图内路由转出（运维诊断等）：本图只做了查询理解，执行权交给目标 agent。
        //     不在这里下发本图的轨迹——目标 agent 会下发自己那条完整轨迹，两发只会互相覆盖。
        if (knowledgeAnswer.isRoutedElsewhere()) {
            log.info("[对话编排] 图内路由转 {}（domain={}），转交该 agent",
                    knowledgeAnswer.routeTarget(), knowledgeAnswer.intent());
            agentBranchDispatcher.runAgentBranch(knowledgeAnswer.routeTarget(), question, conversationId, sink, otelTraceId,
                    knowledgeAnswer.prefillSlots(), null);
            return;
        }

        // 8.2 发送执行轨迹（SSE trace 事件，前端对照面板用；一次性，在流式回答前发出）
        deliveryPortFactory.begin(sink, conversationId, question, otelTraceId, paradigm, trace)
                .emitTrace(conversationId, trace);

        // 8.3 非检索意图（问候/闲聊）：短路直答，不做检索。轨迹已下发——「为什么这轮没查文档」
        //     的答案就在那几步里（意图识别判定为非检索域）
        if (!knowledgeAnswer.needsRetrieval()) {
            log.info("[对话编排] 非检索意图 {}，走闲聊回复", knowledgeAnswer.intent());
            chitchatResponder.handleNonQuery(question, conversationId, sink, otelTraceId);
            return;
        }

        // ========== 9. 空检索处理 ==========
        if (chunks.isEmpty()) {
            log.warn("[对话编排] 检索结果为空，返回降级提示");
            Long emptyMsgId = handleRetrievalEmpty(question, conversationId, sink, otelTraceId);
            agentTraceService.record(conversationId, emptyMsgId, paradigm, question, trace, otelTraceId);
            return;
        }

        // ========== 10. 构建上下文 + 流式回答 ==========
        log.info("[对话编排] 开始流式回答, 上下文共 {} 条", chunks.size());
        ragAnswerStreamer.streamRagResponse(question, conversationId, chunks, sink, startTime,
                paradigm, trace, otelTraceId, answerCacheKey, ruleNormalized);
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

    /** 当前请求的 OTel traceId（{@code rag.chat} 根 span）；仅业务线程内有效，无有效 span 时返回 null。 */
    private static String currentTraceId() {
        var ctx = Span.current().getSpanContext();
        return ctx.isValid() ? ctx.getTraceId() : null;
    }
}
