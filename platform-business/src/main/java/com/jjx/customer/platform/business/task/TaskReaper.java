package com.jjx.customer.platform.business.task;

import com.agentframework.engine.contextmanager.ContextManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.customer.platform.business.engine.persistence.PgEngineSessionStore;
import com.jjx.customer.platform.business.engine.persistence.PgEngineSlotStore;
import com.jjx.customer.platform.business.task.entity.AgentTaskEntity;
import com.jjx.customer.platform.config.properties.AgentProperties;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 诊断任务的周期回收：把"只增不减"从线上摘掉。
 *
 * <p>两件事，各自独立失败：</p>
 * <ol>
 *   <li><b>全局清扫过期任务。</b>惰性 TTL（{@code findActive} 里顺带 expire）只在某个会话被<b>再次访问</b>时
 *       清它自己——再也没人访问的会话永远留着。尤其进程被杀留下的 {@code RUNNING}
 *       （{@code release} 只在异常路径执行，崩溃时不走），它会一直让该任务看起来"有 attempt 在跑"。</li>
 *   <li><b>回收终态任务的引擎执行态。</b>删除其各 attempt 的引擎会话与槽位快照——那是
 *       scratchpad / 阶段产出 / 游标，属**原始执行态**；诊断结论已沉淀在
 *       {@code sa_agent_finding}（结构化主张）+ {@code sa_message}（结论原文）
 *       + {@code sa_agent_trace}（轨迹），审计链不断。</li>
 * </ol>
 *
 * <p><b>回收默认关闭</b>（{@code rag.chat.agent.engine-retention-hours} 未配置或 ≤0）：
 * 删除是破坏性操作，必须显式开启。关闭时仍然扫描并打印可回收条数，便于先观察再决定。</p>
 *
 * <p>多实例下每个实例都会跑本扫描。清扫与回收都是按主键/时间条件的幂等 UPDATE/DELETE，
 * 重复执行只是白做功，不会造成错误状态。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskReaper {

    private final TaskReaperStore taskMapper;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    /** 只取"按会话 id 释放内存"这一面——不依赖 24 个方法的 {@code Engine}。 */
    private final ContextManager contextManager;

    /** 周期扫描：默认 60 分钟一次，启动后 5 分钟首跑（避开启动期资源竞争）。 */
    @Scheduled(fixedDelayString = "${rag.chat.agent.reaper-interval-minutes:60}",
            initialDelayString = "${rag.chat.agent.reaper-initial-delay-minutes:5}",
            timeUnit = TimeUnit.MINUTES)
    public void sweep() {
        abandonStaleTasks();
        reclaimEngineState();
        reclaimOrphanEngineState();
    }

    /** 单批孤儿回收上限（避免一次删太多撑爆事务）。 */
    private static final int ORPHAN_BATCH = 500;

    /**
     * 回收<b>孤儿</b>引擎执行态：不属于任何任务的会话。
     *
     * <p>按 Task 反推的回收永远找不到它们——它们不挂在任何 Task 上。来源是历史遗留的两类：
     * {@code ephemeral} 未生效时每次知识问答 / REST 单轮生成的随机 UUID，
     * 以及更早的 {@code ops-<conversationId>} 格式。</p>
     *
     * <p>实测这批孤儿是真正的存储占用大户：{@code ops_engine_slots} 4175 行约 18 KB/行，
     * 合计 76 MB；而同期 {@code sa_message}（用户以为的问题所在）只有 1.1 MB。</p>
     */
    private void reclaimOrphanEngineState() {
        int retentionHours = agentProperties.engineRetentionHoursEffective();
        List<String> orphans;
        try {
            orphans = taskMapper.findOrphanEngineSessions(retentionHours, ORPHAN_BATCH);
        } catch (Exception ex) {
            log.warn("[reaper] 查询孤儿引擎会话失败（下轮重试）: {}", ex.getMessage());
            return;
        }
        if (orphans.isEmpty()) {
            return;
        }
        if (retentionHours <= 0) {
            log.info("[reaper] 发现 {} 个孤儿引擎会话（不属于任何任务、且已过保留期），但**回收未启用**",
                    orphans.size());
            return;
        }
        PgEngineSessionStore sessionStore = new PgEngineSessionStore(jdbcTemplate, objectMapper);
        PgEngineSlotStore slotStore = new PgEngineSlotStore(jdbcTemplate, objectMapper);
        int deleted = 0;
        for (String sessionId : orphans) {
            try {
                evictEngineState(sessionStore, slotStore, sessionId);
                deleted++;
            } catch (Exception ex) {
                log.warn("[reaper] 回收孤儿引擎会话失败: {} {}", sessionId, ex.getMessage());
            }
        }
        log.info("[reaper] 回收孤儿引擎执行态 {} 个（不属于任何任务的历史遗留）", deleted);
    }

    /** 删引擎态（会话 + 槽位）并摘掉本实例的内存缓存。 */
    private void evictEngineState(PgEngineSessionStore sessionStore, PgEngineSlotStore slotStore,
                                  String sessionId) {
        sessionStore.delete(sessionId);
        slotStore.delete(sessionId);
        // `ContextManager.release` 移除 sessions/slots/workspaces 三个 map——本仓此前从未调用过。
        // 不摘的话"回收"只清了库，进程内对象照涨。
        contextManager.release(sessionId);
    }

    /** 全局清扫：跨会话按时间一刀切，把无人问津/卡住的任务置 ABANDONED。 */
    private void abandonStaleTasks() {
        int ttlMinutes = agentProperties.sessionTtlMinutesEffective();
        try {
            int abandoned = taskMapper.abandonStale(ttlMinutes);
            if (abandoned > 0) {
                log.info("[reaper] 清扫过期任务 {} 个（TTL={}min）", abandoned, ttlMinutes);
            }
        } catch (Exception ex) {
            log.warn("[reaper] 过期任务清扫失败（下轮重试）: {}", ex.getMessage());
        }
    }

    /** 回收终态任务的引擎执行态。 */
    private void reclaimEngineState() {
        int retentionHours = agentProperties.engineRetentionHoursEffective();
        List<AgentTaskEntity> candidates;
        try {
            candidates = taskMapper.findReclaimable(retentionHours);
        } catch (Exception ex) {
            log.warn("[reaper] 查询可回收任务失败（下轮重试）: {}", ex.getMessage());
            return;
        }
        if (candidates.isEmpty()) {
            return;
        }
        if (retentionHours <= 0) {
            log.info("[reaper] {} 个终态任务的引擎执行态已过保留期，但**回收未启用**——"
                            + "设置 rag.chat.agent.engine-retention-hours 开启",
                    candidates.size());
            return;
        }
        // 引擎 store 在 AgentEngineConfiguration 里是内联构造的（不是 bean），
        // 这里就地构造——它们只是 JdbcTemplate 的薄包装，构造期建表语句幂等。
        PgEngineSessionStore sessionStore = new PgEngineSessionStore(jdbcTemplate, objectMapper);
        PgEngineSlotStore slotStore = new PgEngineSlotStore(jdbcTemplate, objectMapper);

        int tasks = 0;
        int sessions = 0;
        for (AgentTaskEntity task : candidates) {
            int attempts = task.getAttemptCount() == null ? 0 : task.getAttemptCount();
            for (int n = 1; n <= attempts; n++) {
                String sessionId = AgentTaskState.attemptIdOf(task.getTaskId(), n);
                try {
                    if (sessionStore.delete(sessionId)) {
                        sessions++;
                    }
                    slotStore.delete(sessionId);
                    // 只对终态任务做：CLOSED/ABANDONED 不会被 resume，摘缓存无副作用
                    contextManager.release(sessionId);
                } catch (Exception ex) {
                    // 单个会话删不掉不阻断整轮：标记留到下次，避免把它误标成"已回收"
                    log.warn("[reaper] 回收引擎态失败: {} {}", sessionId, ex.getMessage());
                }
            }
            try {
                taskMapper.markEngineReclaimed(task.getTaskId());
                tasks++;
            } catch (Exception ex) {
                log.warn("[reaper] 标记已回收失败: {} {}", task.getTaskId(), ex.getMessage());
            }
        }
        log.info("[reaper] 回收引擎执行态：{} 个任务 / {} 个会话（保留期 {}h）。"
                        + "诊断结论未受影响——在 sa_agent_finding / sa_message / sa_agent_trace",
                tasks, sessions, retentionHours);
    }
}
