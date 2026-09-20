package com.jjx.customer.platform.business.knowledge;

import com.jjx.customer.platform.business.trace.EngineTraceMapper;

import com.agentframework.engine.core.Engine;
import com.agentframework.engine.core.RunResult;
import com.agentframework.runtime.session.Input;
import com.agentframework.runtime.session.Session;
import com.agentframework.runtime.session.StartOptions;
import com.jjx.customer.platform.business.engine.AgentCatalog;
import com.jjx.customer.platform.business.engine.PromptFingerprintResolver;
import com.jjx.customer.platform.business.engine.AgentFingerprint;
import com.jjx.customer.platform.business.knowledge.workflow.KnowledgeQaGraphFactory;
import com.jjx.customer.platform.business.knowledge.workflow.KnowledgeReactGraphFactory;
import com.jjx.customer.platform.business.knowledge.RewritePolicy;
import com.jjx.customer.platform.business.trace.model.TraceView;
import com.jjx.customer.platform.knowledge.retrieval.RetrievedChunk;
import com.jjx.customer.platform.knowledge.retrieval.SearchChannelType;
import com.jjx.customer.platform.knowledge.retrieval.SearchContext;
import com.jjx.customer.platform.knowledge.tools.RetrievalTool;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 知识问答主线执行器（业务 → 新内核引擎的适配层）。
 *
 * <p>职责最小化：把编排层算好的 {@link SearchContext} 与查询理解所需的外部事实
 * （会话历史、用户补充、路由上下文）经 {@code Input.slots} 交给引擎（knowledge 轴 =
 * 查询理解链 + 检索反思环，react_loop 轴 = 查询理解链 + think→act→decide 工具循环），
 * 再把结构化命中（{@code kb_chunks_data} / {@code react_tool_chunks} 槽位）无损映射回
 * 管线既有的 {@code RetrievedChunk / TraceView}——流式生成、引用渲染、答案缓存、eval
 * 全部沿用既有实现（执行权在新内核）。单轮检索用 ephemeral 会话，不落引擎会话表。</p>
 *
 * <p><b>改写的归属变了</b>：先前编排层先跑一遍 LLM 改写、把结果塞进 {@code rewritten_query}
 * 槽位；现在这一步是图里的 {@code kb_rewrite} 节点，所以本类不再预置该槽位——
 * 检索工具取到的查询由节点写入，反射轮的重写也才可能真正回灌到检索。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeRunner {

    /** 管线检索参数（SearchContext）在引擎槽位里的键。 */
    public static final String ATTR_SEARCH_CONTEXT = RetrievalTool.SEARCH_CONTEXT_SLOT;

    private final Engine agentEngine;
    private final PromptFingerprintResolver fingerprintResolver;

    /**
     * 一次问答的运行上下文：查询理解链的输入。
     *
     * @param history          最近对话历史（可为空串；kb_rewrite 据此决定是否消解指代）
     * @param clarify          用户在本轮追问节点的补充（可空）
     * @param traceId          正则提取的 traceId（路由规则短路判定用，可空）
     * @param agentChoice      用户显式选择的范式（可空）
     * @param sessionAgentType 活动追问会话的 agentType（可空）
     * @param rewritePolicy    首轮改写策略（可空 = AUTO）；评测靠它跑"改写开/关"对照
     * @param forceRetrieval   强制检索：不让领域路由把本轮转给别的 agent（评测专用，见下）
     */
    public record RunContext(String history, String clarify, String traceId,
                             String agentChoice, String sessionAgentType,
                             RewritePolicy rewritePolicy, boolean forceRetrieval) {

        public RunContext {
            rewritePolicy = rewritePolicy == null ? RewritePolicy.AUTO : rewritePolicy;
        }

        /** 空上下文（无历史、无补充、无路由外部事实、改写走默认策略、不强制检索）。 */
        public static RunContext empty() {
            return new RunContext(null, null, null, null, null, RewritePolicy.AUTO, false);
        }

        /**
         * 评测上下文：给定改写策略，并把本轮钉死在知识检索上。
         *
         * @param rewritePolicy 改写策略
         * @return 评测运行上下文
         */
        public static RunContext forEval(RewritePolicy rewritePolicy) {
            return new RunContext(null, null, null, null, null, rewritePolicy, true);
        }
    }

    /**
     * 一次知识问答检索的执行产物。
     *
     * @param chunks         最终命中（knowledge 轴取 {@code kb_chunks_data}，react 轴取 {@code react_tool_chunks}）
     * @param trace          执行轨迹（查询理解链 + 检索/循环 + 终态）
     * @param intent         意图域（路由节点判定结果）
     * @param needsRetrieval 意图是否要求检索（false = 问候/闲聊，编排层走直答）
     * @param routeTarget    路由目标；非 {@code knowledge} 表示本图未检索、由编排层接手
     * @param prefillSlots   路由预填槽位（如 traceId）
     */
    public record KnowledgeAnswer(List<RetrievedChunk> chunks, TraceView trace,
                                  String intent, boolean needsRetrieval, String routeTarget,
                                  Map<String, String> prefillSlots) {

        public boolean isEmpty() {
            return chunks.isEmpty();
        }

        /** 本图是否把本轮交给了别的执行者（运维诊断等）。 */
        public boolean isRoutedElsewhere() {
            return routeTarget != null && !routeTarget.isBlank()
                    && !AgentCatalog.KNOWLEDGE.id().equals(routeTarget);
        }
    }

    /** 执行知识问答主线（缺省 knowledge 轴）。 */
    public KnowledgeAnswer retrieve(String question, SearchContext searchContext) {
        return retrieve(question, searchContext, AgentCatalog.KNOWLEDGE.id(), RunContext.empty());
    }

    /** 按范式轴执行（knowledge / react_loop 都是引擎图，差异只在节点形态）。 */
    public KnowledgeAnswer retrieve(String question, SearchContext searchContext, String paradigm) {
        return retrieve(question, searchContext, paradigm, RunContext.empty());
    }

    /**
     * 按范式轴执行，并带上查询理解链所需的运行上下文。
     *
     * @param question      用户原始问题
     * @param searchContext 检索参数（集合 / 预算 / 阈值；查询本身由图内节点产生）
     * @param paradigm      范式轴（knowledge / react_loop）
     * @param runContext    运行上下文（历史 / 补充 / 路由外部事实）
     * @return 执行产物
     */
    public KnowledgeAnswer retrieve(String question, SearchContext searchContext, String paradigm,
                                    RunContext runContext) {
        // 解析后再取 id：历史别名（naive/react）经 AgentCatalog 解析成当前范式，
        // 后面取出口槽位必须用解析后的 id——用原始入参会让 react 别名跑到工具循环图、
        // 却按 knowledge 的槽位去读，命中被判成空。
        String requested = paradigm == null || paradigm.isBlank() ? null : paradigm;
        AgentCatalog.Entry entry = AgentCatalog.byId(requested).orElse(AgentCatalog.KNOWLEDGE);
        String agentId = entry.id();

        Map<String, Object> slots = new LinkedHashMap<>();
        slots.put(KnowledgeQaGraphFactory.QUESTION_SLOT, question);
        if (searchContext != null) {
            slots.put(ATTR_SEARCH_CONTEXT, searchContext);
        }
        RunContext ctx = runContext == null ? RunContext.empty() : runContext;
        putIfPresent(slots, KnowledgeQaGraphFactory.HISTORY_SLOT, ctx.history());
        putIfPresent(slots, KnowledgeQaGraphFactory.CLARIFY_SLOT, ctx.clarify());
        putIfPresent(slots, KnowledgeQaGraphFactory.TRACE_ID_SLOT, ctx.traceId());
        putIfPresent(slots, KnowledgeQaGraphFactory.AGENT_CHOICE_SLOT, ctx.agentChoice());
        putIfPresent(slots, KnowledgeQaGraphFactory.SESSION_AGENT_SLOT, ctx.sessionAgentType());
        slots.put(KnowledgeQaGraphFactory.REWRITE_POLICY_SLOT, ctx.rewritePolicy().name());
        if (ctx.forceRetrieval()) {
            slots.put(KnowledgeQaGraphFactory.FORCE_RETRIEVAL_SLOT, true);
        }

        // 单轮检索问答：ephemeral 会话（不落引擎会话表）。
        // 必须显式 startSession + asEphemeral()——agentEngine.run(agentId, input) 内部走的是
        // StartOptions.defaults()（ephemeral=false），于是**每一次知识问答**都会生成一个随机 UUID
        // 会话，往 ops_engine_session / ops_engine_slots 各写一行且永不删除。
        // 这是高频路径（每次提问都走），泄漏速度远高于 ops 的单轮 REST。
        Session singleTurn = agentEngine.startSession(
                agentEngine.loadAgent(entry.id(), "latest"),
                StartOptions.defaults().asEphemeral()
                        // 引擎会话/事件/span 根挂到编排链 traceId 上（RunContext 已携带；
                        // 评测等无链路径为空，引擎自生成——withTraceId 对空白安全）
                        .withTraceId(ctx.traceId()));
        RunResult result = agentEngine.run(singleTurn, new Input(question, Map.of(), slots));

        List<RetrievedChunk> chunks = toChunks(result, agentId);
        AgentFingerprint fingerprint = new AgentFingerprint(entry.id(), entry.workflowId(),
                fingerprintResolver.promptHash(entry.id()));
        return new KnowledgeAnswer(chunks, EngineTraceMapper.toBusinessTrace(result, fingerprint),
                stringSlot(result, KnowledgeQaGraphFactory.INTENT_SLOT),
                booleanSlot(result, KnowledgeQaGraphFactory.NEEDS_RETRIEVAL_SLOT, true),
                stringSlot(result, KnowledgeQaGraphFactory.ROUTE_TARGET_SLOT),
                prefillOf(result));
    }

    /** 空串与缺失同等对待：路由规则按 null 判断"规则输入不可用"。 */
    private static void putIfPresent(Map<String, Object> slots, String key, String value) {
        if (value != null && !value.isBlank()) {
            slots.put(key, value);
        }
    }

    private static String stringSlot(RunResult result, String key) {
        Object value = result.slots() == null ? null : result.slots().get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** 布尔槽位；缺失时取缺省（图未跑到该节点 = 保守按"需要检索"处理）。 */
    private static boolean booleanSlot(RunResult result, String key, boolean fallback) {
        Object value = result.slots() == null ? null : result.slots().get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    /** 路由预填槽位（route_prefill 是 OBJECT 槽，缺失时给空表）。 */
    private static Map<String, String> prefillOf(RunResult result) {
        Object value = result.slots() == null ? null : result.slots().get(
                KnowledgeQaGraphFactory.ROUTE_PREFILL_SLOT);
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<String, String> prefill = new LinkedHashMap<>();
        map.forEach((k, v) -> prefill.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
        return prefill;
    }

    /**
     * 出口槽位 → RetrievedChunk。两轴的槽名、形状、语义都不同，各自取对：
     *
     * <ul>
     *   <li><b>knowledge</b> — 优先 {@code kb_chunks_all}（kb_critique 每轮并入的<b>跨轮累积集</b>，ARRAY）；
     *       为空时回退 {@code kb_chunks_data}（TOOL 节点<b>单轮</b>派生槽，OBJECT{chunks:[…]}）。
     *       单轮槽每轮覆盖写：反思轮空手而归时会把首轮正确命中一并抹掉，故不能只读它。</li>
     *   <li><b>react</b> — {@code react_tool_chunks}（ActExecutor 每轮 append 的裸 List，ARRAY）。</li>
     * </ul>
     *
     * <p>槽名一律取自图声明常量（Region 前缀展开的结果），别写字面量以免两处漂移。
     * 形状两种都要认：早先只认 Map，react 轴的 List 恒落到空返回，图内 kb_done 报"命中 N 条"
     * 而 runner 读到空，该轴检索指标恒 0。</p>
     */
    private static List<RetrievedChunk> toChunks(RunResult result, String agentId) {
        if (AgentCatalog.REACT.id().equals(agentId)) {
            return chunksOf(result, KnowledgeReactGraphFactory.TOOL_CHUNKS_SLOT);
        }
        List<RetrievedChunk> all = chunksOf(result, KnowledgeQaGraphFactory.CHUNKS_ALL_SLOT);
        return all.isEmpty() ? chunksOf(result, KnowledgeQaGraphFactory.CHUNKS_DATA_SLOT) : all;
    }

    /** 从指定槽取分片：兼容 {@code {chunks:[…]}}（OBJECT 派生槽）与裸 List（ARRAY 槽）两种形状。 */
    private static List<RetrievedChunk> chunksOf(RunResult result, String slot) {
        Object data = result.slots() == null ? null : result.slots().get(slot);
        List<?> list;
        if (data instanceof Map<?, ?> wrapper && wrapper.get("chunks") instanceof List<?> nested) {
            list = nested;
        } else if (data instanceof List<?> flat) {
            list = flat;
        } else {
            return List.of();
        }
        List<RetrievedChunk> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> doc) {
                out.add(toChunk(doc));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static RetrievedChunk toChunk(Map<?, ?> doc) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        Object rawMetadata = doc.get("metadata");
        if (rawMetadata instanceof Map<?, ?> meta) {
            meta.forEach((k, v) -> metadata.put(String.valueOf(k), v));
        }
        Object docName = doc.get("docName");
        if (docName != null) {
            metadata.putIfAbsent("doc_name", docName);
        }
        Object channel = doc.get("channel");
        Object content = doc.get("content");
        Object score = doc.get("score");
        return new RetrievedChunk(
                content == null ? "" : String.valueOf(content),
                score instanceof Number number ? number.doubleValue() : 0.0,
                metadata,
                toChannel(channel == null ? null : String.valueOf(channel)));
    }

    private static SearchChannelType toChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return null;
        }
        try {
            return SearchChannelType.valueOf(channel);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
