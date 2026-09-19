package com.agentframework.definition.node;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 自定义节点：逃生舱口，把执行委托给扩展注册表中名为 {@code executorRef} 的 {@code NodeExecutor}。
 *
 * @param id          节点 id
 * @param executorRef 执行器注册名
 * @param config      执行器配置
 * @param outputSlot  输出槽位名
 * @param meta        横切信息
 */
public record CustomNodeDefinition(
        String id,
        String executorRef,
        Map<String, Object> config,
        String outputSlot,
        NodeMeta meta) implements NodeDefinition {

    public CustomNodeDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("custom node id is required");
        }
        if (executorRef == null || executorRef.isBlank()) {
            throw new IllegalArgumentException("custom node '" + id + "' requires an executorRef");
        }
        config = config == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(config));
        outputSlot = outputSlot == null || outputSlot.isBlank() ? "custom_result" : outputSlot;
        meta = meta == null ? NodeMeta.empty() : meta;
    }

    /**
     * @param id          节点 id
     * @param executorRef 执行器注册名
     * @param outputSlot  输出槽位名
     * @return 自定义节点定义
     */
    public static CustomNodeDefinition of(String id, String executorRef, String outputSlot) {
        return new CustomNodeDefinition(id, executorRef, null, outputSlot, null);
    }

    @Override
    public NodeType type() {
        return NodeType.CUSTOM;
    }
}
