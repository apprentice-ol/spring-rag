package com.nageoffer.ai.rag.eval.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nageoffer.ai.rag.eval.dao.entity.EvalDatasetEntity;
import org.apache.ibatis.annotations.Mapper;

/** 评测数据集 Mapper。 */
@Mapper
public interface EvalDatasetMapper extends BaseMapper<EvalDatasetEntity> {
}
