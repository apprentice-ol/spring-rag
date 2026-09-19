package com.agentframework.engine.workflowruntime;

import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.node.NodeType;
import com.agentframework.engine.core.NodeContext;
import com.agentframework.engine.core.NodeResult;

/**
 * 节点执行器扩展点：每种 {@link NodeType} 对应一个实现。
 */
public interface NodeExecutor {

    /** @return 支持的节点类型 */
    NodeType type();

    /**
     * 执行节点。
     *
     * @param node    节点定义
     * @param context 节点上下文
     * @return 节点结果
     */
    NodeResult execute(NodeDefinition node, NodeContext context);
}
