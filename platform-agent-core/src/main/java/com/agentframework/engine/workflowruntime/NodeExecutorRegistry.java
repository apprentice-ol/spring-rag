package com.agentframework.engine.workflowruntime;

import com.agentframework.definition.node.NodeDefinition;
import com.agentframework.definition.node.NodeType;
import com.agentframework.engine.core.NodeContext;
import com.agentframework.engine.core.NodeResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 节点执行器注册表：按类型与按名称双索引。
 *
 * <p>按名称索引供 {@code CustomNodeDefinition} 的 {@code executorRef} 使用，
 * 让扩展可以自定义节点行为而不必修改 {@link NodeType} 枚举。</p>
 */
public final class NodeExecutorRegistry {

    private final Map<NodeType, NodeExecutor> byType = new LinkedHashMap<>();
    private final Map<String, NodeExecutor> byName = new LinkedHashMap<>();

    /**
     * 按类型注册执行器。
     *
     * @param executor 执行器
     * @return 当前注册表
     */
    public NodeExecutorRegistry register(NodeExecutor executor) {
        if (executor != null) {
            byType.put(executor.type(), executor);
        }
        return this;
    }

    /**
     * 按名称注册执行器。
     *
     * @param name     注册名
     * @param executor 执行器
     * @return 当前注册表
     */
    public NodeExecutorRegistry register(String name, NodeExecutor executor) {
        if (name != null && executor != null) {
            byName.put(name, executor);
        }
        return this;
    }

    /**
     * 按类型解析执行器。
     *
     * @param type 节点类型
     * @return 执行器
     */
    public Optional<NodeExecutor> resolve(NodeType type) {
        return Optional.ofNullable(byType.get(type));
    }

    /**
     * 按名称解析执行器。
     *
     * @param name 注册名
     * @return 执行器
     */
    public Optional<NodeExecutor> resolve(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /** @return 已注册的节点类型 */
    public List<NodeType> types() {
        return List.copyOf(byType.keySet());
    }

    /** @return 已注册的注册名 */
    public List<String> names() {
        return List.copyOf(byName.keySet());
    }

    /**
     * 执行节点。
     *
     * @param node    节点定义
     * @param context 节点上下文
     * @return 节点结果
     * @throws IllegalStateException 未注册对应执行器时抛出
     */
    public NodeResult execute(NodeDefinition node, NodeContext context) {
        NodeExecutor executor = byType.get(node.type());
        if (executor == null) {
            throw new IllegalStateException("未注册节点执行器：" + node.type());
        }
        return executor.execute(node, context);
    }
}
