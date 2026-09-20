package com.jjx.customer.platform.business.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jjx.customer.platform.business.task.entity.AgentFindingEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 诊断主张 Mapper（MyBatis-Plus）。
 *
 * <p>主张<b>只追加</b>：本 Mapper 不提供删除。失效走状态标记（见
 * {@code AgentFindingServiceImpl.markRetracted}），历史必须留得住——审计要能回答
 * "当时凭什么这么说、后来为什么不算数了"。</p>
 */
@Mapper
public interface AgentFindingMapper extends BaseMapper<AgentFindingEntity> {
}
