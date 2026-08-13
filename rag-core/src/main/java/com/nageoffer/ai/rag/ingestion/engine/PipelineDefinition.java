package com.nageoffer.ai.rag.ingestion.engine;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 流水线完整定义（由 DB 中的 pipeline DO + node DO 组装而成）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PipelineDefinition {

    /** 流水线 ID（数字的字符串形式） */
    private String id;

    /** 流水线名称 */
    private String name;

    /** 流水线描述 */
    private String description;

    /** 节点配置列表（按 nextNodeId 构成链） */
    private List<NodeConfig> nodes;

    /** 首个节点 ID（执行入口） */
    private String entryNodeId;

    /**
     * 获取节点 ID → NodeConfig 映射。
     *
     * @return nodeId 到 NodeConfig 的 Map
     */
    public Map<String, NodeConfig> nodeMap() {
        return nodes.stream().collect(Collectors.toMap(NodeConfig::getNodeId, n -> n));
    }
}
