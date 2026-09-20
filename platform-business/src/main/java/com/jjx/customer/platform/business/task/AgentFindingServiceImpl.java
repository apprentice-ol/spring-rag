package com.jjx.customer.platform.business.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.customer.platform.business.task.entity.AgentFindingEntity;
import com.jjx.customer.platform.business.task.mapper.AgentFindingMapper;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 诊断主张存储（{@code sa_agent_finding}）：只追加，失效走状态标记。
 *
 * <p>写入失败只记 warn——丢主张的后果是"下一轮追问上下文变薄"，不是"产出看起来像正常收尾的伪结论"，
 * 与引擎存储（读不到状态会让诊断带空槽位继续跑）的严重性不同，降级口径因此不同。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentFindingServiceImpl {

    private final AgentFindingMapper findingMapper;
    private final ObjectMapper objectMapper;

    /**
     * 落库一批主张；同一任务上原有的生效主张一并标记为被取代。
     *
     * <p>取代发生在<b>新一轮结论产出时</b>：新结论是对旧结论的修订，旧主张不是"错了"
     * 而是"被更新了"——所以标 {@code SUPERSEDED} 而非 {@code RETRACTED}。后者留给
     * 用户明确否定（见 {@link #markRetracted}），两者审计含义不同。</p>
     *
     * @param findings 本次抽取出的主张（空列表 = 只做取代，不新增）
     */
    public void replaceActiveWith(List<AgentFinding> findings) {
        if (findings == null || findings.isEmpty()) {
            return;
        }
        String taskId = findings.getFirst().taskId();
        try {
            supersedeActive(taskId);
            int saved = 0;
            for (AgentFinding finding : findings) {
                findingMapper.insert(toEntity(finding));
                saved++;
            }
            log.info("[findings] 任务 {} 落库 {} 条主张（第 {} 次 attempt）",
                    taskId, saved, findings.getFirst().attemptNo());
        } catch (Exception ex) {
            log.warn("[findings] 主张落库失败（下一轮追问的上下文会变薄，本轮结论不受影响）: 任务={} {}",
                    taskId, ex.getMessage());
        }
    }

    /**
     * 该任务当前生效的主张（按产出顺序）。
     *
     * @param taskId 任务标识
     * @return 主张列表；查询失败 = 空列表
     */
    public List<AgentFinding> activeOf(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return List.of();
        }
        try {
            return findingMapper.selectList(new LambdaQueryWrapper<AgentFindingEntity>()
                            .eq(AgentFindingEntity::getTaskId, taskId)
                            .eq(AgentFindingEntity::getStatus, AgentFinding.Status.ACTIVE.name())
                            .orderByAsc(AgentFindingEntity::getAttemptNo)
                            .orderByAsc(AgentFindingEntity::getCreateTime))
                    .stream().map(this::toFinding).toList();
        } catch (Exception ex) {
            log.warn("[findings] 读取生效主张失败（按无主张处理）: 任务={} {}", taskId, ex.getMessage());
            return List.of();
        }
    }

    /**
     * 判定某条主张为错（用户明确否定时调用）。
     *
     * <p>与 {@code SUPERSEDED} 的区别：那是"被更新"，这是"被推翻"。留痕不同，
     * 事后复盘要能看出当时是演进还是纠错。</p>
     *
     * @param findingId 主张标识
     * @return 是否命中
     */
    public boolean markRetracted(String findingId) {
        return updateStatus(findingId, AgentFinding.Status.RETRACTED);
    }

    /** 把该任务上生效的主张全部标记为被取代。 */
    private void supersedeActive(String taskId) {
        AgentFindingEntity patch = new AgentFindingEntity();
        patch.setStatus(AgentFinding.Status.SUPERSEDED.name());
        patch.setUpdateTime(LocalDateTime.now());
        findingMapper.update(patch, new LambdaQueryWrapper<AgentFindingEntity>()
                .eq(AgentFindingEntity::getTaskId, taskId)
                .eq(AgentFindingEntity::getStatus, AgentFinding.Status.ACTIVE.name()));
    }

    private boolean updateStatus(String findingId, AgentFinding.Status status) {
        if (findingId == null || findingId.isBlank()) {
            return false;
        }
        try {
            AgentFindingEntity patch = new AgentFindingEntity();
            patch.setStatus(status.name());
            patch.setUpdateTime(LocalDateTime.now());
            return findingMapper.update(patch, new LambdaQueryWrapper<AgentFindingEntity>()
                    .eq(AgentFindingEntity::getFindingId, findingId)) > 0;
        } catch (Exception ex) {
            log.warn("[findings] 更新主张状态失败: {} {}", findingId, ex.getMessage());
            return false;
        }
    }

    private AgentFindingEntity toEntity(AgentFinding finding) {
        AgentFindingEntity e = new AgentFindingEntity();
        e.setFindingId(finding.findingId());
        e.setTaskId(finding.taskId());
        e.setConversationId(finding.conversationId());
        e.setKind(finding.kind().name());
        e.setClaim(finding.claim());
        e.setStatus(finding.status().name());
        e.setAttemptNo(finding.attemptNo());
        e.setCreateTime(LocalDateTime.now());
        e.setUpdateTime(LocalDateTime.now());
        try {
            e.setEvidence(objectMapper.writeValueAsString(finding.evidence()));
        } catch (Exception ex) {
            e.setEvidence("[]");
        }
        return e;
    }

    private AgentFinding toFinding(AgentFindingEntity e) {
        return new AgentFinding(e.getFindingId(), e.getTaskId(), e.getConversationId(),
                parseKind(e.getKind()), e.getClaim(), readEvidence(e.getEvidence()),
                parseStatus(e.getStatus()), e.getAttemptNo() == null ? 0 : e.getAttemptNo());
    }

    private static AgentFinding.Kind parseKind(String kind) {
        try {
            return kind == null ? AgentFinding.Kind.ROOT_CAUSE : AgentFinding.Kind.valueOf(kind);
        } catch (IllegalArgumentException ex) {
            return AgentFinding.Kind.ROOT_CAUSE;
        }
    }

    private static AgentFinding.Status parseStatus(String status) {
        try {
            return status == null ? AgentFinding.Status.ACTIVE : AgentFinding.Status.valueOf(status);
        } catch (IllegalArgumentException ex) {
            return AgentFinding.Status.ACTIVE;
        }
    }

    private List<AgentFinding.Evidence> readEvidence(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<AgentFinding.Evidence>>() {
            });
        } catch (Exception ex) {
            return List.of();
        }
    }
}
