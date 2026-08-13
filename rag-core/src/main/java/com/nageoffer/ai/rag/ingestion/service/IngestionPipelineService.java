package com.nageoffer.ai.rag.ingestion.service;

import com.nageoffer.ai.rag.ingestion.domain.entity.IngestionPipelineEntity;
import com.nageoffer.ai.rag.ingestion.engine.NodeConfig;
import com.nageoffer.ai.rag.ingestion.engine.PipelineDefinition;
import java.util.List;

/** 流水线服务：DB 驱动（P2 从内存硬编码升级为读 sa_ingestion_pipeline*）。 */
public interface IngestionPipelineService {

    /** 按 id 或 name 拿节点链。 */
    PipelineDefinition getDefinition(String pipelineId);

    Long createPipeline(String name, String description, List<NodeConfig> nodes);

    IngestionPipelineEntity getPipeline(Long id);

    List<IngestionPipelineEntity> listPipelines();

    void deletePipeline(Long id);
}
