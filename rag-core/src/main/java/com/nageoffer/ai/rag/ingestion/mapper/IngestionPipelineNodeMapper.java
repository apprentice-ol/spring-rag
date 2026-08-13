package com.nageoffer.ai.rag.ingestion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nageoffer.ai.rag.ingestion.domain.entity.IngestionPipelineNodeEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 流水线节点 Mapper。 */
@Mapper
public interface IngestionPipelineNodeMapper extends BaseMapper<IngestionPipelineNodeEntity> {

    /** 物理删除某 pipeline 的全部节点（绕过逻辑删除，配合 pipeline 物理删除）。 */
    @Delete("DELETE FROM sa_ingestion_pipeline_node WHERE pipeline_id = #{pipelineId}")
    int deletePhysicalByPipelineId(@Param("pipelineId") Long pipelineId);
}
