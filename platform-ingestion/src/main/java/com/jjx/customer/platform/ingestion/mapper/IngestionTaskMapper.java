package com.jjx.customer.platform.ingestion.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jjx.customer.platform.ingestion.domain.entity.IngestionTaskEntity;
import org.apache.ibatis.annotations.Mapper;

/** 入库任务 Mapper。 */
@Mapper
public interface IngestionTaskMapper extends BaseMapper<IngestionTaskEntity> {
}
