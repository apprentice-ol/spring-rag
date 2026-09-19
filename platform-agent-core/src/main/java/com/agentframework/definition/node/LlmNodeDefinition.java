package com.agentframework.definition.node;

import com.agentframework.definition.agent.ModelConfig;

/**
 * LLM 节点：用解析后的 Prompt 调用模型，并把回答写入 {@code outputSlot}。
 *
 * @param id            节点 id
 * @param promptRef     Prompt 资产 id
 * @param promptVersion Prompt 版本，缺省为 {@code latest}
 * @param outputSlot    模型输出写入的槽位名
 * @param modelOverride 节点级模型覆盖，null 表示沿用 Agent 配置
 * @param meta          横切信息
 */
public record LlmNodeDefinition(
        String id,
        String promptRef,
        String promptVersion,
        String outputSlot,
        ModelConfig modelOverride,
        NodeMeta meta) implements NodeDefinition {

    public LlmNodeDefinition {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("llm node id is required");
        }
        if (promptRef == null || promptRef.isBlank()) {
            throw new IllegalArgumentException("llm node '" + id + "' requires a promptRef");
        }
        promptVersion = promptVersion == null ? "latest" : promptVersion;
        outputSlot = outputSlot == null || outputSlot.isBlank() ? "output" : outputSlot;
        meta = meta == null ? NodeMeta.empty() : meta;
    }

    /**
     * @param id         节点 id
     * @param promptRef  Prompt 资产 id
     * @param outputSlot 输出槽位名
     * @return LLM 节点定义
     */
    public static LlmNodeDefinition of(String id, String promptRef, String outputSlot) {
        return new LlmNodeDefinition(id, promptRef, null, outputSlot, null, null);
    }

    @Override
    public NodeType type() {
        return NodeType.LLM;
    }

    /** Prompt assets are versioned: pinning is a config change, not a code change. */
    /**
     * 固定 Prompt 版本。Prompt 是资产，版本固定属于配置变更而非代码变更。
     *
     * @param version Prompt 版本号
     * @return 固定版本后的节点定义
     */
    public LlmNodeDefinition withPromptVersion(String version) {
        return new LlmNodeDefinition(id, promptRef, version, outputSlot, modelOverride, meta);
    }

    /**
     * @param modelOverride 节点级模型配置
     * @return 覆盖模型后的节点定义
     */
    public LlmNodeDefinition withModel(ModelConfig modelOverride) {
        return new LlmNodeDefinition(id, promptRef, promptVersion, outputSlot, modelOverride, meta);
    }

    /**
     * @param meta 横切信息
     * @return 覆盖横切信息后的节点定义
     */
    public LlmNodeDefinition withMeta(NodeMeta meta) {
        return new LlmNodeDefinition(id, promptRef, promptVersion, outputSlot, modelOverride, meta);
    }

    /**
     * @param outputSlot 输出槽位名
     * @return 覆盖输出槽位后的节点定义
     */
    public LlmNodeDefinition withOutputSlot(String outputSlot) {
        return new LlmNodeDefinition(id, promptRef, promptVersion, outputSlot, modelOverride, meta);
    }
}
