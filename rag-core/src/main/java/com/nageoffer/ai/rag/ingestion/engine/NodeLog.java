package com.nageoffer.ai.rag.ingestion.engine;

import java.util.Map;
import lombok.Builder;
import lombok.Data;

/** 单个节点的执行日志（写入 sa_ingestion_task_node）。 */
@Data
@Builder
public class NodeLog {

    private String nodeId;
    private String nodeType;
    private String message;
    private long durationMs;
    private boolean success;
    private String error;
    private Map<String, Object> output;
}
