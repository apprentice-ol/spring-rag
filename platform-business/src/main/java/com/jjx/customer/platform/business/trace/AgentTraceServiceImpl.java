package com.jjx.customer.platform.business.trace;
import com.jjx.customer.platform.business.trace.entity.AgentTraceEntity;
import com.jjx.customer.platform.business.trace.mapper.AgentTraceMapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.customer.platform.business.trace.model.TraceView;
import com.jjx.customer.platform.common.dto.PageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTraceServiceImpl implements AgentTraceService {

    private final AgentTraceMapper agentTraceMapper;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void record(String conversationId, Long messageId, String paradigm, String question, TraceView trace, String traceId) {
        if (trace == null) {
            return;
        }
        try {
            AgentTraceEntity agentTraceEntity = new AgentTraceEntity();
            agentTraceEntity.setConversationId(conversationId);
            agentTraceEntity.setMessageId(messageId);
            agentTraceEntity.setTraceId(traceId);
            agentTraceEntity.setParadigm(paradigm);
            agentTraceEntity.setWorkflowId(trace.getWorkflowId());
            agentTraceEntity.setPromptHash(trace.getPromptHash());
            agentTraceEntity.setQuestion(question);
            agentTraceEntity.setSteps(objectMapper.writeValueAsString(trace.getSteps()));
            agentTraceEntity.setLlmCallCount(trace.getLlmCallCount());
            agentTraceEntity.setTotalLatencyMs(trace.getTotalLatencyMs());
            agentTraceEntity.setCreateTime(LocalDateTime.now());
            agentTraceMapper.insert(agentTraceEntity);
        } catch (Exception ex) {
            // 轨迹落库失败不影响对话主流程
            log.warn("[TraceView] 落库失败（不影响对话）: {}", ex.getMessage());
        }
    }

    @Override
    public AgentTraceEntity getByMessageId(Long messageId) {
        if (messageId == null) {
            return null;
        }
        return agentTraceMapper.selectOne(new LambdaQueryWrapper<AgentTraceEntity>()
                .eq(AgentTraceEntity::getMessageId, messageId)
                .orderByDesc(AgentTraceEntity::getId)
                .last("LIMIT 1"));
    }

    @Override
    public Map<Long, String> traceIdsByMessageIds(Collection<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return Map.of();
        }
        List<AgentTraceEntity> rows = agentTraceMapper.selectList(
                new LambdaQueryWrapper<AgentTraceEntity>()
                        .in(AgentTraceEntity::getMessageId, messageIds)
                        .isNotNull(AgentTraceEntity::getTraceId)
                        .select(AgentTraceEntity::getMessageId, AgentTraceEntity::getTraceId)
                        .orderByAsc(AgentTraceEntity::getId));
        Map<Long, String> traceIds = new HashMap<>();
        for (AgentTraceEntity row : rows) {
            traceIds.putIfAbsent(row.getMessageId(), row.getTraceId());
        }
        return traceIds;
    }

    @Override
    public PageResult<AgentTraceEntity> page(int page, int size, String paradigm, String keyword) {
        page = Math.max(page, 1);
        size = Math.min(Math.max(size, 1), 100);
        // count 用干净 wrapper（selectCount 保留 ORDER BY 在 PG 下会报错）
        long total = agentTraceMapper.selectCount(buildWrapper(paradigm, keyword));
        List<AgentTraceEntity> records = agentTraceMapper.selectList(
                buildWrapper(paradigm, keyword)
                        .orderByDesc(AgentTraceEntity::getId)
                        .last("LIMIT " + size + " OFFSET " + (long) (page - 1) * size));
        return new PageResult<>(total, records);
    }

    private LambdaQueryWrapper<AgentTraceEntity> buildWrapper(String paradigm, String keyword) {
        LambdaQueryWrapper<AgentTraceEntity> qw = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(paradigm)) {
            qw.eq(AgentTraceEntity::getParadigm, paradigm);
        }
        if (StringUtils.hasText(keyword)) {
            qw.like(AgentTraceEntity::getQuestion, keyword);
        }
        return qw;
    }

    @Override
    public AgentTraceEntity get(Long id) {
        return agentTraceMapper.selectById(id);
    }

    @Override
    public List<Map<String, Object>> stats() {
        // 按 paradigm 分组：条数 + 平均步数（jsonb_array_length 对 steps 数组）
        return jdbcTemplate.queryForList(
                "SELECT paradigm, count(*) AS cnt, coalesce(avg(jsonb_array_length(steps)), 0) AS avg_steps "
                        + "FROM sa_agent_trace WHERE steps IS NOT NULL GROUP BY paradigm ORDER BY cnt DESC");
    }
}
