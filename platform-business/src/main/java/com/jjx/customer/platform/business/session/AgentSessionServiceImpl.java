package com.jjx.customer.platform.business.session;

import com.jjx.customer.platform.business.session.entity.AgentSessionEntity;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.customer.platform.business.session.mapper.AgentSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 澄清会话存储（sa_agent_session）：读写侧方法签名与旧内核 {@code SessionStore}
 * 契约一致，改为业务自持（写入侧由 {@code FrameworkOpsRunner} 在引擎出口调用——
 * 挂起/升级写 AWAITING_USER，终态写 DONE，替代旧 {@code SessionRecordingListener}）。
 *
 * <p>失败一律 warn + 降级（empty / false / 忽略）——会话状态丢失只影响"恢复追问"体验，不阻断对话主链路。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentSessionServiceImpl {

    private final AgentSessionMapper sessionMapper;
    private final ObjectMapper objectMapper;
    private final com.jjx.customer.platform.config.properties.AgentProperties agentProperties;

    /**
     * 查询活动（AWAITING_USER）会话；TTL 过期即清除并按无状态处理。
     */
    public Optional<AgentSessionState> findActive(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        try {
            AgentSessionEntity e = sessionMapper.selectOne(new LambdaQueryWrapper<AgentSessionEntity>()
                    .eq(AgentSessionEntity::getConversationId, sessionId)
                    .eq(AgentSessionEntity::getStatus, AgentSessionState.Status.AWAITING_USER.name())
                    .orderByDesc(AgentSessionEntity::getId)
                    .last("LIMIT 1"));
            if (e == null) {
                return Optional.empty();
            }
            int ttl = agentProperties.sessionTtlMinutesEffective();
            if (sessionMapper.expire(sessionId, ttl) > 0) {
                log.info("[AgentSession] 会话状态已过期(TTL={}min): {}", ttl, sessionId);
                return Optional.empty();
            }
            return Optional.of(toState(e));
        } catch (Exception ex) {
            log.warn("[AgentSession] 查询活动状态失败（按无状态处理）: {}", ex.getMessage());
            return Optional.empty();
        }
    }


    public boolean claim(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        try {
            return sessionMapper.claim(sessionId) > 0;
        } catch (Exception ex) {
            log.warn("[AgentSession] 占位失败（按无状态处理）: {}", ex.getMessage());
            return false;
        }
    }


    public void saveAwaitingUser(AgentSessionState state) {
        if (state == null || state.sessionId() == null || state.sessionId().isBlank()) {
            return;
        }
        try {
            AgentSessionEntity agentSessionEntity = sessionMapper.selectOne(new LambdaQueryWrapper<AgentSessionEntity>()
                    .eq(AgentSessionEntity::getConversationId, state.sessionId())
                    .last("LIMIT 1"));
            boolean create = (agentSessionEntity == null);
            if (create) {
                agentSessionEntity = new AgentSessionEntity();
                agentSessionEntity.setConversationId(state.sessionId());
                agentSessionEntity.setCreateTime(LocalDateTime.now());
            }
            agentSessionEntity.setAgentType(state.agentId());
            agentSessionEntity.setStage(state.stage());
            agentSessionEntity.setStatus(AgentSessionState.Status.AWAITING_USER.name());
            agentSessionEntity.setSlots(objectMapper.writeValueAsString(state.slots()));
            agentSessionEntity.setMissingSlots(objectMapper.writeValueAsString(state.missingSlots()));
            agentSessionEntity.setSummary(state.summary());
            agentSessionEntity.setUpdateTime(LocalDateTime.now());
            if (create) {
                sessionMapper.insert(agentSessionEntity);
            } else {
                sessionMapper.updateById(agentSessionEntity);
            }
        } catch (Exception ex) {
            log.warn("[AgentSession] 保存会话状态失败（不影响对话）: {}", ex.getMessage());
        }
    }


    public void complete(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            AgentSessionEntity e = sessionMapper.selectOne(new LambdaQueryWrapper<AgentSessionEntity>()
                    .eq(AgentSessionEntity::getConversationId, sessionId)
                    .last("LIMIT 1"));
            if (e != null) {
                e.setStatus(AgentSessionState.Status.DONE.name());
                e.setUpdateTime(LocalDateTime.now());
                sessionMapper.updateById(e);
            }
        } catch (Exception ex) {
            log.warn("[AgentSession] 完成标记失败: {}", ex.getMessage());
        }
    }

    /** DB 实体 → 会话契约模型（键映射：conversationId → sessionId，agentType → agentId）。 */
    private AgentSessionState toState(AgentSessionEntity e) {
        return new AgentSessionState(e.getConversationId(), e.getAgentType(), e.getStage(),
                parseStatus(e.getStatus()), readSlots(e.getSlots()), readNames(e.getMissingSlots()), e.getSummary());
    }

    private static AgentSessionState.Status parseStatus(String status) {
        try {
            return status == null ? AgentSessionState.Status.AWAITING_USER
                    : AgentSessionState.Status.valueOf(status);
        } catch (IllegalArgumentException ex) {
            return AgentSessionState.Status.AWAITING_USER;
        }
    }

    private Map<String, String> readSlots(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private List<String> readNames(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception ex) {
            return List.of();
        }
    }
}
