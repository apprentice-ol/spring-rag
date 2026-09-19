package com.agentframework.definition.node;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具节点：调用一个工具。
 *
 * <p>参数值可以是字面量，也可以是 {@code ${slots.name}} 形式的引用，运行时按当前槽位解析。</p>
 *
 * @param id          节点 id
 * @param toolRef     工具 id
 * @param toolVersion 工具版本，缺省为 {@code latest}
 * @param arguments   工具参数，支持字面量与 {@code ${...}} 引用
 * @param outputSlot  工具结果写入的槽位名
 * @param meta        横切信息
 */
public record ToolNodeDefinition(
        String id,
        String toolRef,
        String toolVersion,
        Map<String, Object> arguments,
        String outputSlot,
        NodeMeta meta) implements NodeDefinition {

    public ToolNodeDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("tool node id is required");
        }
        if (toolRef == null || toolRef.isBlank()) {
            throw new IllegalArgumentException("tool node '" + id + "' requires a toolRef");
        }
        toolVersion = toolVersion == null ? "latest" : toolVersion;
        arguments = arguments == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        outputSlot = outputSlot == null || outputSlot.isBlank() ? "tool_result" : outputSlot;
        meta = meta == null ? NodeMeta.empty() : meta;
    }

    /**
     * @param id         节点 id
     * @param toolRef    工具 id
     * @param outputSlot 输出槽位名
     * @return 工具节点定义
     */
    public static ToolNodeDefinition of(String id, String toolRef, String outputSlot) {
        return new ToolNodeDefinition(id, toolRef, null, null, outputSlot, null);
    }

    @Override
    public NodeType type() {
        return NodeType.TOOL;
    }

    /**
     * @param name  参数名
     * @param value 参数值，可为字面量或 {@code ${...}} 引用
     * @return 追加参数后的节点定义
     */
    public ToolNodeDefinition withArgument(String name, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(arguments);
        merged.put(name, value);
        return new ToolNodeDefinition(id, toolRef, toolVersion, merged, outputSlot, meta);
    }

    /**
     * @param meta 横切信息
     * @return 覆盖横切信息后的节点定义
     */
    public ToolNodeDefinition withMeta(NodeMeta meta) {
        return new ToolNodeDefinition(id, toolRef, toolVersion, arguments, outputSlot, meta);
    }
}
