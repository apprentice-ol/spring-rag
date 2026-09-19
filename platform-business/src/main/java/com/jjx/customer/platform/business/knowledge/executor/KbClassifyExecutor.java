package com.jjx.customer.platform.business.knowledge.executor;

import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.node.NodeType;
import com.agentframework.engine.core.NodeContext;
import com.agentframework.engine.core.NodeResult;
import com.agentframework.engine.workflowruntime.NodeExecutor;
import com.jjx.customer.platform.business.knowledge.KnowledgeQaGraphFactory;
import com.jjx.customer.platform.business.orchestration.intent.IntentClassifier;
import com.jjx.customer.platform.intent.IntentResult;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 意图识别节点执行器：LLM 判定问题所属域，产出域标识 + 置信度 + 联网标记。
 *
 * <p>先前这一步在编排层过程式执行，轨迹里看不见；放进图后，「这句话被理解成了什么意图、
 * 有多大把握」成为轨迹里的一等公民——它正是"为什么这轮没走检索"或"为什么被送去诊断"
 * 的直接答案。</p>
 *
 * <p>域只落标量（{@code intent} / {@code intent_confidence} / {@code needs_web_search} /
 * {@code intent_reason}），不落 {@code IntentResult} 对象：槽位写入会随
 * {@code ExecutionTraceStep.toDocument()} 进 SSE 轨迹，塞对象等于把内部结构摊给前端。</p>
 */
public class KbClassifyExecutor implements NodeExecutor {

    private final IntentClassifier intentClassifier;

    /**
     * @param intentClassifier 意图分类器（可能被缓存装饰，规则短路时零 LLM）
     */
    public KbClassifyExecutor(IntentClassifier intentClassifier) {
        this.intentClassifier = intentClassifier;
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
        IntentResult intent = intentClassifier.classify(normalized);

        String domain = intent.getDomain() == null ? IntentResult.DOMAIN_KNOWLEDGE : intent.getDomain();
        Map<String, Object> writes = new LinkedHashMap<>();
        writes.put(KnowledgeQaGraphFactory.INTENT_SLOT, domain);
        writes.put(KnowledgeQaGraphFactory.INTENT_CONFIDENCE_SLOT, intent.getConfidence());
        writes.put(KnowledgeQaGraphFactory.NEEDS_WEB_SEARCH_SLOT, intent.isNeedsWebSearch());
        // 派生标记：闸门据此拦住问候/闲聊，不劳 route_target 再表达一次
        writes.put(KnowledgeQaGraphFactory.NEEDS_RETRIEVAL_SLOT, intent.needsRetrieval());
        if (intent.getReason() != null && !intent.getReason().isBlank()) {
            writes.put(KnowledgeQaGraphFactory.INTENT_REASON_SLOT, intent.getReason());
        }
        // 输出文本即轨迹里那一行「out:」——分类理由比域标识更能说明问题
        String output = intent.getReason() == null || intent.getReason().isBlank()
                ? String.format("%s（置信度 %.2f）", domain, intent.getConfidence())
                : String.format("%s（置信度 %.2f）· %s", domain, intent.getConfidence(), intent.getReason());
        return NodeResult.completed(node.id(), output, writes);
    }
}
