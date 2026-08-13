package com.nageoffer.ai.rag.ingestion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nageoffer.ai.rag.ingestion.domain.entity.DocumentEntity;
import org.apache.ibatis.annotations.Mapper;

/** 文档 Mapper。 */
@Mapper
public interface DocumentMapper extends BaseMapper<DocumentEntity> {
}
