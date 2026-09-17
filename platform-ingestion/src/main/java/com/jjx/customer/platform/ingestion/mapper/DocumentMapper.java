package com.jjx.customer.platform.ingestion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jjx.customer.platform.ingestion.domain.entity.DocumentEntity;
import org.apache.ibatis.annotations.Mapper;

/** 文档 Mapper。 */
@Mapper
public interface DocumentMapper extends BaseMapper<DocumentEntity> {
}
