package com.jjx.customer.platform.business.knowledge.node;

import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.node.NodeType;
import com.agentframework.engine.core.NodeContext;
import com.agentframework.engine.core.NodeResult;
import com.agentframework.engine.workflowruntime.NodeExecutor;
import com.jjx.customer.platform.business.knowledge.workflow.KnowledgeQaGraphFactory;
import com.jjx.customer.platform.common.util.QueryNormalizer;
import java.util.Map;

/**
 * 查询归一化节点执行器：规则清洗后写 {@code normalized_query}，供意图分类与改写引用。
 *
 * <p>纯规则、零 LLM（{@link QueryNormalizer}），因此这一节点在图里几乎不占耗时——
 * 它出现在轨迹里的价值不是"耗时"，而是让「用户原话 → 真正送去检索的查询」这一跳可见。</p>
 *
 * <p>编排层为答案缓存 key 也调一次同一个纯函数（见 {@code ChatOrchestrator}）：
 * 缓存查询必须发生在检索之前，而那时图还没跑。两处调用是同一输入上的同一纯函数，
 * 结果必然一致，不是"两套口径"。</p>
 */
public class KbNormalizeExecutor implements NodeExecutor {

    @Override
    public NodeType type() {
        return NodeType.CUSTOM;
    }

    @Override
    public NodeResult execute(NodeDefinition node, NodeContext context) {
        String text = context.input() == null || context.input().text() == null
                ? "" : context.input().text();
        String normalized = QueryNormalizer.normalize(text);
        if (normalized.isBlank()) {
            // 全标点/纯噪声输入会被规则清成空串——退回原文，别让后续节点拿到空查询
            normalized = text.trim();
        }
        return NodeResult.completed(node.id(), normalized,
                Map.of(KnowledgeQaGraphFactory.NORMALIZED_QUERY_SLOT, normalized));
    }
}
