package com.agentframework.engine.core;

import com.agentframework.definition.node.NodeDefinition;

/**
 * 单个节点调用入口：由工作流运行时实现，供并行节点与子工作流节点复用同一套执行管道。
 *
 * <p>这样“节点怎么执行”只有一份实现，并行与子工作流不会绕过守卫、过滤器与拦截器。</p>
 */
@FunctionalInterface
public interface BranchInvoker {

    /**
     * 执行一个节点（含守卫、过滤器与拦截器）。
     *
     * @param node    节点定义
     * @param context 节点上下文
     * @return 节点结果
     */
    NodeResult invokeNode(NodeDefinition node, NodeContext context);
}
