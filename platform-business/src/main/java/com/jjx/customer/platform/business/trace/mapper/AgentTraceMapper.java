package com.jjx.customer.platform.business.trace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jjx.customer.platform.business.trace.entity.AgentTraceEntity;
import org.apache.ibatis.annotations.Mapper;

/** Agent 执行轨迹 Mapper（sa_agent_trace）。 */
@Mapper
public interface AgentTraceMapper extends BaseMapper<AgentTraceEntity> {
}
