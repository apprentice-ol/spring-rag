package com.jjx.customer.platform.business.session.mapper;
import com.jjx.customer.platform.business.session.entity.AgentSessionEntity;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

/**
 * Agent 会话状态 Mapper（MyBatis-Plus）。
 */
@Mapper
public interface AgentSessionMapper extends BaseMapper<AgentSessionEntity> {

    /**
     * 乐观占位：AWAITING_USER → RUNNING。返回行数=1 表示抢占成功（并发双请求只有一个继续）。
     */
    @Update("UPDATE sa_agent_session SET status = 'RUNNING', update_time = NOW() "
            + "WHERE conversation_id = #{conversationId} AND status = 'AWAITING_USER'")
    int claim(String conversationId);

    /** TTL 过期：AWAITING_USER 且超时 → EXPIRED。 */
    @Update("UPDATE sa_agent_session SET status = 'EXPIRED', update_time = NOW() "
            + "WHERE conversation_id = #{conversationId} AND status = 'AWAITING_USER' "
            + "AND update_time < NOW() - (#{ttlMinutes} || ' minutes')::interval")
    int expire(String conversationId, int ttlMinutes);
}
