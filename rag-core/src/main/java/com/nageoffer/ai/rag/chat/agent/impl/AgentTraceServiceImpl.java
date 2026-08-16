package com.nageoffer.ai.rag.chat.agent.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.rag.chat.agent.AgentTrace;
import com.nageoffer.ai.rag.chat.agent.AgentTraceService;
import com.nageoffer.ai.rag.chat.dao.entity.AgentTraceEntity;
import com.nageoffer.ai.rag.chat.dao.mapper.AgentTraceMapper;
import com.nageoffer.ai.rag.ingestion.domain.dto.PageResult;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTraceServiceImpl implements AgentTraceService {

    private final AgentTraceMapper agentTraceMapper;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void record(String conversationId, Long messageId, String paradigm, String question, AgentTrace trace, String traceId) {
        if (trace == null) {
            return;
        }
        try {
            AgentTraceEntity e = new AgentTraceEntity();
            e.setConversationId(conversationId);
            e.setMessageId(messageId);
            e.setTraceId(traceId);
            e.setParadigm(paradigm);
            e.setQuestion(question);
            e.setSteps(objectMapper.writeValueAsString(trace.getSteps()));
            e.setLlmCallCount(trace.getLlmCallCount());
            e.setTotalLatencyMs(trace.getTotalLatencyMs());
            e.setCreateTime(LocalDateTime.now());
            agentTraceMapper.insert(e);
        } catch (Exception ex) {
            // 轨迹落库失败不影响对话主流程
            log.warn("[AgentTrace] 落库失败（不影响对话）: {}", ex.getMessage());
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
