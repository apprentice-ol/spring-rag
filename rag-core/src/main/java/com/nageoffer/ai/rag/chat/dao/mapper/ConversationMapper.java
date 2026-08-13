package com.nageoffer.ai.rag.chat.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nageoffer.ai.rag.chat.dao.entity.ConversationEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConversationMapper extends BaseMapper<ConversationEntity> {
}
