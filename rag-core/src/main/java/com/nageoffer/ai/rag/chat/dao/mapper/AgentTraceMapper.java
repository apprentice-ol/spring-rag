package com.nageoffer.ai.rag.chat.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nageoffer.ai.rag.chat.dao.entity.AgentTraceEntity;
import org.apache.ibatis.annotations.Mapper;

/** Agent 执行轨迹 Mapper（sa_agent_trace）。 */
@Mapper
public interface AgentTraceMapper extends BaseMapper<AgentTraceEntity> {
}
