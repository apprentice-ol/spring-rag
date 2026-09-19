package com.jjx.customer.platform.business.knowledge.executor;

import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.node.NodeType;
import com.agentframework.engine.core.NodeContext;
import com.agentframework.engine.core.NodeResult;
import com.agentframework.engine.workflowruntime.NodeExecutor;
import com.jjx.customer.platform.business.engine.AgentCatalog;
import com.jjx.customer.platform.business.knowledge.KnowledgeQaGraphFactory;
import com.jjx.customer.platform.intent.IntentResult;
import java.util.Map;

/**
 * 短路终态节点执行器：本轮不由本图检索时，给图一个显式出口。
 *
 * <p>两类情况走这里：<b>非检索意图</b>（问候/闲聊——生成走编排层的闲聊流式路径）
 * 与<b>路由转出</b>（如运维诊断——执行权在另一个 agent）。两种情况的内容都不在本图生成，
 * 所以这个节点只做一件事：把"为什么没检索"写成一行，让轨迹有个交代，
 * 编排层再从 {@code route_target} 槽位读走该接手的活。</p>
 */
public class KbShortCircuitExecutor implements NodeExecutor {

    @Override
    public NodeType type() {
        return NodeType.CUSTOM;
    }

    @Override
    public NodeResult execute(NodeDefinition node, NodeContext context) {
        String target = context.slots().getString(KnowledgeQaGraphFactory.ROUTE_TARGET_SLOT,
                AgentCatalog.KNOWLEDGE.id());
        String intent = context.slots().getString(KnowledgeQaGraphFactory.INTENT_SLOT, null);

        String reason;
        if (!AgentCatalog.KNOWLEDGE.id().equals(target)) {
            reason = "路由转出：" + target + "（含预填槽位 "
                    + context.slots().getString(KnowledgeQaGraphFactory.ROUTE_PREFILL_SLOT, "无") + "）";
        } else if (intent != null && !IntentResult.DOMAIN_KNOWLEDGE.equals(intent)) {
            reason = "非检索意图 " + intent + "，不经检索直接回复";
        } else {
            reason = "短路退出";
        }
        return NodeResult.completed(node.id(), reason, Map.of());
    }
}
