package com.nageoffer.ai.rag.eval.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nageoffer.ai.rag.eval.dao.entity.EvalMetricEntity;
import org.apache.ibatis.annotations.Mapper;

/** 评测指标 Mapper。 */
@Mapper
public interface EvalMetricMapper extends BaseMapper<EvalMetricEntity> {
}
