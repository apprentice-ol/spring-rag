package com.nageoffer.ai.rag.ingestion.engine;

/**
 * 入库节点接口（完整版，搬自原 ragent）。
 *
 * <p>{@code getNodeType()} 返回节点类型字符串（fetcher/parser/chunker/enhancer/enricher/indexer），
 * 引擎按此匹配 NodeConfig.nodeType。
 */
public interface IngestionNode {

    String getNodeType();

    NodeResult execute(IngestionContext context, NodeConfig config);
}
