package com.nageoffer.ai.rag.ingestion.collection.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nageoffer.ai.rag.ingestion.collection.domain.entity.DocCollectionEntity;
import org.apache.ibatis.annotations.Mapper;

/** 文档集合 Mapper。 */
@Mapper
public interface DocCollectionMapper extends BaseMapper<DocCollectionEntity> {
}
