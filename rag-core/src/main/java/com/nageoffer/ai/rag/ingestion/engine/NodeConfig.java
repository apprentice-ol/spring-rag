package com.nageoffer.ai.rag.ingestion.engine;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 流水线节点配置项（对应 ingestion_pipeline_node 表中的一条节点记录）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeConfig {

    /** 节点 ID（流水线内唯一） */
    private String nodeId;

    /** 节点类型：fetcher / parser / chunker / enhancer / enricher / indexer */
    private String nodeType;

    /** 下一节点 ID */
    private String nextNodeId;

    /** 节点配置 JSON（由 DB settings_json 反序列化） */
    private JsonNode settings;

    /** 节点执行条件 JSON（由 DB condition_json 反序列化） */
    private JsonNode condition;
}
