package com.jjx.customer.platform.business.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.customer.platform.business.ops.AutonomyLevel;
import com.jjx.customer.platform.business.task.entity.AgentTaskEntity;
import com.jjx.customer.platform.business.task.mapper.AgentTaskMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Agent 任务存储（{@code sa_agent_task}）：四层结构里的「工作边界」。
 *
 * <p><b>与旧 {@code AgentSessionServiceImpl} 的核心差别</b>：旧表把「一次诊断」等同于
 * 「一个会话」（{@code conversation_id} 上有 UNIQUE），于是诊断一出结论就把行置 DONE、
 * 下一轮消息既恢复不了也带不走槽位。本服务把 Task 做成<b>可跨 attempt 沿用</b>的工作单元：
 * 挂起 → 恢复同一 attempt；已出结论 → 同 Task 开新 attempt（槽位照带）。
 * 这正是"追问之下上下文全丢"那个问题的修复点。</p>
 *
 * <p>查询/写入失败的降级口径沿用既有约定（warn + 降级，不阻断对话主链路）——
 * 与引擎存储不同，这里丢的是"任务归属"，最坏退化成"开一个新任务"，
 * 不会产出看起来像正常收尾的伪结论。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentTaskServiceImpl {

    /** 可沿用的状态（{@code RUNNING} 也查出来——用来识别"有 attempt 在跑"）。 */
    private static final List<String> ACTIVE_STATUSES = List.of(
            AgentTaskState.Status.OPEN.name(),
            AgentTaskState.Status.RUNNING.name(),
            AgentTaskState.Status.SUSPENDED.name(),
            AgentTaskState.Status.CONCLUDED.name());

    private final AgentTaskMapper taskMapper;
    private final ObjectMapper objectMapper;
    private final com.jjx.customer.platform.config.properties.AgentProperties agentProperties;

    /**
     * 查该对话下可沿用的最新任务。
     *
     * <p>{@code CONCLUDED} 也在结果里——<b>这是"诊断出结论后追问"能接上的关键</b>：
     * 旧实现只认 AWAITING_USER，收尾即 DONE，下一轮什么都带不走。</p>
     *
     * @param conversationId 对话标识
     * @return 任务状态；无 / 查询失败 = empty
     */
    public Optional<AgentTaskState> findActive(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return Optional.empty();
        }
        try {
            // 惰性 TTL（与旧实现同口径，不引入定时任务）
            taskMapper.expire(conversationId, agentProperties.sessionTtlMinutesEffective());
            AgentTaskEntity e = taskMapper.selectOne(new LambdaQueryWrapper<AgentTaskEntity>()
                    .eq(AgentTaskEntity::getConversationId, conversationId)
                    .in(AgentTaskEntity::getStatus, ACTIVE_STATUSES)
                    .orderByDesc(AgentTaskEntity::getCreateTime)
                    .last("LIMIT 1"));
            return e == null ? Optional.empty() : Optional.of(toState(e));
        } catch (Exception ex) {
            log.warn("[AgentTask] 查询活动任务失败（按无任务处理）: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 新建任务（首个 attempt 尚未开始）。
     *
     * @param conversationId 对话标识
     * @param agentId        agent 范式
     * @return 新任务状态；写入失败返回 null（调用方按"无法诊断"处理）
     */
    public AgentTaskState create(String conversationId, String agentId) {
        AgentTaskEntity e = new AgentTaskEntity();
        e.setTaskId(UUID.randomUUID().toString());
        e.setConversationId(conversationId);
        e.setAgentType(agentId);
        e.setStatus(AgentTaskState.Status.OPEN.name());
        e.setSlots("{}");
        e.setAttemptCount(0);
        e.setCreateTime(LocalDateTime.now());
        e.setUpdateTime(LocalDateTime.now());
        try {
            taskMapper.insert(e);
            return toState(e);
        } catch (Exception ex) {
            log.warn("[AgentTask] 创建任务失败: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * 按 id 取任务当前状态。
     *
     * <p>与 {@link #findActive} 的区别：那个按「可沿用状态」过滤，运行期（RUNNING）查不到；
     * 本方法不看状态——收尾后要读权威的 {@code attemptCount} 来标注主张来源，那时正处 RUNNING。</p>
     *
     * @param taskId 任务标识
     * @return 任务状态；不存在/查询失败 = empty
     */
    public Optional<AgentTaskState> findById(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Optional.empty();
        }
        try {
            AgentTaskEntity e = taskMapper.selectById(taskId);
            return e == null ? Optional.empty() : Optional.of(toState(e));
        } catch (Exception ex) {
            log.warn("[AgentTask] 按 id 查任务失败: {} {}", taskId, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 恢复路径的抢占：只从「可沿用」状态抢到的那一个请求能继续。
     *
     * @param taskId 任务标识
     * @return 是否抢到
     */
    public boolean claim(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return false;
        }
        try {
            return taskMapper.claim(taskId) > 0;
        } catch (Exception ex) {
            log.warn("[AgentTask] 占位失败（按无任务处理）: {}", ex.getMessage());
            return false;
        }
    }

    /**
     * 开启新 attempt：抢占 + 计数递增，返回新 attempt 序号。
     *
     * <p><b>不吞异常</b>——返回 -1 专指"被并发请求抢先"，DB 故障一律上抛。
     * 两者混淆正是"读不到 ≠ 没有"那类静默故障的来源。</p>
     *
     * @param taskId 任务标识
     * @return attempt 序号（从 1 起）；-1 = 未抢到（已有 attempt 在跑）
     */
    public int beginAttempt(String taskId) {
        Integer attemptNo = taskMapper.beginAttempt(taskId);
        return attemptNo == null ? -1 : attemptNo;
    }

    /**
     * 释放占位（幂等）：抢占后本轮没跑起来时把任务放回可恢复态，
     * 避免永久停在 RUNNING（旧实现里 {@code findActive} 只看 AWAITING_USER，
     * 卡住的行会静默遗弃整个会话）。
     *
     * @param taskId 任务标识
     */
    public void release(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        try {
            if (taskMapper.release(taskId) > 0) {
                log.warn("[AgentTask] 占位已释放（本轮未跑起来，任务回到可恢复态）: {}", taskId);
            }
        } catch (Exception ex) {
            log.warn("[AgentTask] 释放占位失败: {}", ex.getMessage());
        }
    }

    /**
     * 落挂起态：状态置 SUSPENDED 并保存槽位快照。
     *
     * <p><b>不变量：本方法不写 {@code attempt_count}。</b>调用方传来的 {@code state} 由本轮
     * <i>开始前</i>读到的任务构造，其 {@code attemptCount} 是旧值——而 {@code beginAttempt}
     * 已在执行期把库里的序号原子递增过。照抄旧值会把序号冲回去，表现为"追问永远开不出第 2 个
     * attempt"。序号只由 {@code beginAttempt} 维护。</p>
     *
     * @param state 任务状态（含本轮已确认槽位；其中 {@code attemptCount} 不参与写入）
     */
    public void markSuspended(AgentTaskState state) {
        if (state == null || state.taskId() == null) {
            return;
        }
        try {
            AgentTaskEntity e = taskMapper.selectById(state.taskId());
            if (e == null) {
                return;
            }
            e.setStatus(AgentTaskState.Status.SUSPENDED.name());
            e.setStage(state.stage());
            e.setSlots(objectMapper.writeValueAsString(state.slots()));
            if (state.autonomyLevel() != null && !state.autonomyLevel().isBlank()) {
                e.setAutonomyLevel(state.autonomyLevel());
            }
            // 链 id 只在首轮写（恢复轮沿用同一个，别被本轮的 otelTraceId 顶掉）
            if (e.getChainTraceId() == null && state.chainTraceId() != null
                    && !state.chainTraceId().isBlank()) {
                e.setChainTraceId(state.chainTraceId());
            }
            e.setUpdateTime(LocalDateTime.now());
            taskMapper.updateById(e);
        } catch (Exception ex) {
            log.warn("[AgentTask] 保存挂起态失败（不影响对话）: {}", ex.getMessage());
        }
    }

    /**
     * 落结论态：状态置 CONCLUDED（<b>不是 CLOSED</b>）。
     *
     * <p>语义差别是这次改造的核心——"系统给出了结论"不等于"目标达成"，
     * 后者由人判定。留成 CONCLUDED，下一轮追问就能沿用同一 Task 与槽位。</p>
     *
     * @param taskId     任务标识
     * @param conclusion 结论摘要
     */
    public void markConcluded(String taskId, String conclusion) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        try {
            AgentTaskEntity e = taskMapper.selectById(taskId);
            if (e == null) {
                return;
            }
            e.setStatus(AgentTaskState.Status.CONCLUDED.name());
            e.setSummary(conclusion);
            e.setUpdateTime(LocalDateTime.now());
            taskMapper.updateById(e);
        } catch (Exception ex) {
            log.warn("[AgentTask] 保存结论态失败: {}", ex.getMessage());
        }
    }

    /**
     * 会话自主档位（人在环中 P3）：读该对话最近一条任务已存的档位。
     *
     * @param conversationId 对话标识
     * @return 档位（查不到/异常 = 缺省 L2，绝不阻断对话）
     */
    public AutonomyLevel autonomyOf(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return AutonomyLevel.L2;
        }
        try {
            AgentTaskEntity e = taskMapper.selectOne(new LambdaQueryWrapper<AgentTaskEntity>()
                    .eq(AgentTaskEntity::getConversationId, conversationId)
                    .orderByDesc(AgentTaskEntity::getCreateTime)
                    .last("LIMIT 1"));
            return AutonomyLevel.parse(e == null ? null : e.getAutonomyLevel());
        } catch (Exception ex) {
            log.warn("[AgentTask] 读取自主档位失败（按缺省 L2）: {}", ex.getMessage());
            return AutonomyLevel.L2;
        }
    }

    /** DB 实体 → 任务契约模型。 */
    private AgentTaskState toState(AgentTaskEntity e) {
        return new AgentTaskState(e.getTaskId(), e.getConversationId(), e.getAgentType(), e.getStage(),
                parseStatus(e.getStatus()), readSlots(e.getSlots()), e.getSummary(),
                e.getAutonomyLevel(), e.getChainTraceId(),
                e.getAttemptCount() == null ? 0 : e.getAttemptCount());
    }

    private static AgentTaskState.Status parseStatus(String status) {
        try {
            return status == null ? AgentTaskState.Status.OPEN
                    : AgentTaskState.Status.valueOf(status);
        } catch (IllegalArgumentException ex) {
            return AgentTaskState.Status.OPEN;
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
}
