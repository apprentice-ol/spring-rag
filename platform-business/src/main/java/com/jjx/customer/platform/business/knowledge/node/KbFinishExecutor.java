package com.jjx.customer.platform.business.knowledge.node;
import com.jjx.customer.platform.business.knowledge.workflow.KnowledgeReactGraphFactory;
import com.jjx.customer.platform.business.knowledge.workflow.KnowledgeQaGraphFactory;

import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.node.NodeType;
import com.agentframework.engine.core.NodeContext;
import com.agentframework.engine.core.NodeResult;
import com.agentframework.engine.workflowruntime.NodeExecutor;
import java.util.List;
import java.util.Map;

/**
 * knowledge 线统一终态节点执行器：无副作用收尾（检索命中已在槽位，生成交付段在引擎外）。
 *
 * <p>终态节点的存在意义是给图一个显式出口（{@code terminal=true}）；
 * 产出 = 检索命中的文本预览（trace 可读），结构化数据走 {@code kb_chunks_data} /
 * {@code react_tool_chunks} 槽位由 runner 消费。</p>
 */
public class KbFinishExecutor implements NodeExecutor {

    /** 执行器注册名（图声明引用）。 */
    public static final String EXECUTOR_REF = "kb-finish";

    /**
     * 命中槽优先级：最先取到分片的那个。
     *
     * <p>knowledge 轴优先<b>跨轮累积槽</b>（{@code kb_critique} 每轮并入）——单轮槽每轮覆盖写，
     * 反思轮空手而归那轮会把数字打到 0，报出来的条数与出口层实际取到的分片对不上。
     * 槽名一律用图声明常量，别写字面量以免两处漂移。</p>
     */
    private static final List<String> CHUNK_SLOTS = List.of(
            KnowledgeQaGraphFactory.CHUNKS_ALL_SLOT,
            KnowledgeQaGraphFactory.CHUNKS_DATA_SLOT,
            KnowledgeReactGraphFactory.TOOL_CHUNKS_SLOT);

    @Override
    public NodeType type() {
        return NodeType.CUSTOM;
    }

    @Override
    public NodeResult execute(NodeDefinition node, NodeContext context) {
        // 终态只出一行统计（命中数）——观测全文已在检索步，不重复铺开
        int count = 0;
        for (String slot : CHUNK_SLOTS) {
            int size = sizeOf(context.slots().get(slot));
            if (size >= 0) {
                count = size;
                break;
            }
        }
        String text = count > 0 ? "检索完成 · 命中 " + count + " 条" : "检索完成（无命中）";
        return NodeResult.completed(node.id(), text);
    }

    /** 槽内存量分片数：{@code {chunks:[…]}}（OBJECT 派生槽）与裸 List（ARRAY 槽）都认；缺失返回 -1。 */
    private static int sizeOf(Object data) {
        if (data instanceof Map<?, ?> wrapper && wrapper.get("chunks") instanceof List<?> list) {
            return list.size();
        }
        return data instanceof List<?> list ? list.size() : -1;
    }
}
