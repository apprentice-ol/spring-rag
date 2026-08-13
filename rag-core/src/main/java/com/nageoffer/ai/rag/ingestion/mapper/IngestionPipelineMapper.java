package com.nageoffer.ai.rag.ingestion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nageoffer.ai.rag.ingestion.domain.entity.IngestionPipelineEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 流水线模板 Mapper。 */
@Mapper
public interface IngestionPipelineMapper extends BaseMapper<IngestionPipelineEntity> {

    /**
     * 物理删除（绕过 @TableLogic 逻辑删除）。
     * <p>
     * pipeline 的 name 有全局唯一约束，逻辑删除后记录仍占用 name，导致无法重建同名 pipeline
     * （Bootstrap 重建 default 时会撞 duplicate key）。配置表无回收站需求，删除一律物理删除。
     */
    @Delete("DELETE FROM sa_ingestion_pipeline WHERE id = #{id}")
    int deletePhysical(@Param("id") Long id);
}
