package com.jjx.customer.platform.business.knowledge.node;

import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.node.NodeType;
import com.agentframework.engine.core.NodeContext;
import com.agentframework.engine.core.NodeResult;
import com.agentframework.engine.workflowruntime.NodeExecutor;
import com.jjx.customer.platform.business.engine.AgentCatalog;
import com.jjx.customer.platform.business.knowledge.workflow.KnowledgeQaGraphFactory;
import com.jjx.customer.platform.intent.IntentResult;
import com.jjx.customer.platform.routing.RouteContext;
import com.jjx.customer.platform.routing.RouteDecision;
import com.jjx.customer.platform.routing.RouteRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 路由判定节点执行器：意图就绪后求值规则表，产出本轮该走哪个执行者。
 *
 * <p>这是编排层两次规则表求值里的<b>第二次</b>（第一次是意图分类前的正则短路，
 * 那一次留在编排层——它决定的是"要不要进这张图"，进了图就无从谈起）。
 * 本节点把 {@link RouteRegistry} 的结果落成两个槽位：</p>
 * <ul>
 *   <li>{@code route_target} — 目标执行者 id；{@code knowledge} 表示留在本图继续检索，
 *       其余值（{@code ops_diagnose} 等）由编排层接手，图走 {@code kb_shortcircuit} 终态。</li>
 *   <li>{@code route_prefill} — 规则预填槽位（如消息里正则提取的 traceId），
 *       随目标 agent 的槽位目录合并。</li>
 * </ul>
 */
public class KbRouteExecutor implements NodeExecutor {

    private final RouteRegistry routeRegistry;

    /**
     * @param routeRegistry 路由规则表
     */
    public KbRouteExecutor(RouteRegistry routeRegistry) {
        this.routeRegistry = routeRegistry;
    }

    @Override
    public NodeType type() {
        return NodeType.CUSTOM;
    }

    @Override
    public NodeResult execute(NodeDefinition node, NodeContext context) {
        String question = context.slots().getString(KnowledgeQaGraphFactory.QUESTION_SLOT, "");
        String normalized = context.slots().getString(
                KnowledgeQaGraphFactory.NORMALIZED_QUERY_SLOT, question);
        IntentResult intent = rebuildIntent(context);

        // 评测：把本轮钉死在知识检索上，不让分类器的域判断把题转走（转走 = 空 chunks = 静默 0 分）
        if (context.slots().getBoolean(KnowledgeQaGraphFactory.FORCE_RETRIEVAL_SLOT, false)) {
            Map<String, Object> forced = new LinkedHashMap<>();
            forced.put(KnowledgeQaGraphFactory.ROUTE_TARGET_SLOT, AgentCatalog.KNOWLEDGE.id());
            return NodeResult.completed(node.id(),
                    "评测强制检索（跳过域路由，意图域 " + intent.getDomain() + "）", forced);
        }
        Optional<RouteDecision> decision = routeRegistry.evaluate(new RouteContext(
                question,
                normalized,
                intent,
                blankToNull(context.slots().getString(KnowledgeQaGraphFactory.TRACE_ID_SLOT, null)),
                blankToNull(context.slots().getString(KnowledgeQaGraphFactory.SESSION_AGENT_SLOT, null)),
                blankToNull(context.slots().getString(KnowledgeQaGraphFactory.AGENT_CHOICE_SLOT, null))));

        String target = decision.map(RouteDecision::agentType).orElse(AgentCatalog.KNOWLEDGE.id());
        Map<String, String> prefill = decision.map(RouteDecision::prefillSlots).orElse(Map.of());

        Map<String, Object> writes = new LinkedHashMap<>();
        writes.put(KnowledgeQaGraphFactory.ROUTE_TARGET_SLOT, target);
        if (!prefill.isEmpty()) {
            writes.put(KnowledgeQaGraphFactory.ROUTE_PREFILL_SLOT, new LinkedHashMap<>(prefill));
        }
        return NodeResult.completed(node.id(), describe(target, intent), writes);
    }

    /** 由标量槽位重建意图结果（分类节点刻意只落标量，见 {@link KbClassifyExecutor}）。 */
    private static IntentResult rebuildIntent(NodeContext context) {
        Object confidence = context.slots().get(KnowledgeQaGraphFactory.INTENT_CONFIDENCE_SLOT);
        return IntentResult.builder()
                .domain(context.slots().getString(KnowledgeQaGraphFactory.INTENT_SLOT,
                        IntentResult.DOMAIN_KNOWLEDGE))
                .confidence(confidence instanceof Number number ? number.doubleValue() : 0.0)
                .needsWebSearch(context.slots().getBoolean(
                        KnowledgeQaGraphFactory.NEEDS_WEB_SEARCH_SLOT, false))
                .reason(context.slots().getString(KnowledgeQaGraphFactory.INTENT_REASON_SLOT, null))
                .build();
    }

    /** 轨迹里那一行「out:」：说清走到哪、按什么走。 */
    private static String describe(String target, IntentResult intent) {
        if (target == null || target.isBlank() || AgentCatalog.KNOWLEDGE.id().equals(target)) {
            return intent.needsRetrieval()
                    ? "知识检索（意图域 " + intent.getDomain() + "）"
                    : "非检索意图 " + intent.getDomain() + "，短路直答";
        }
        return "转 " + target;
    }

    /** 空串与缺失同等对待：路由规则按 null 判断"规则输入不可用"。 */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
