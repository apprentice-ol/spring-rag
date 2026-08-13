package com.nageoffer.ai.rag.ingestion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nageoffer.ai.rag.ingestion.domain.entity.IngestionTaskNodeEntity;
import org.apache.ibatis.annotations.Mapper;

/** 任务节点 Mapper。 */
@Mapper
public interface IngestionTaskNodeMapper extends BaseMapper<IngestionTaskNodeEntity> {
}
