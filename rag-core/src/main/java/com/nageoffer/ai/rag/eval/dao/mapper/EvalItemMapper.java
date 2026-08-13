package com.nageoffer.ai.rag.eval.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nageoffer.ai.rag.eval.dao.entity.EvalItemEntity;
import org.apache.ibatis.annotations.Mapper;

/** 评测条目 Mapper。 */
@Mapper
public interface EvalItemMapper extends BaseMapper<EvalItemEntity> {
}
