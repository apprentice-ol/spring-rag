package com.nageoffer.ai.rag.eval.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nageoffer.ai.rag.eval.dao.entity.EvalRunEntity;
import org.apache.ibatis.annotations.Mapper;

/** 评测运行 Mapper。 */
@Mapper
public interface EvalRunMapper extends BaseMapper<EvalRunEntity> {
}
