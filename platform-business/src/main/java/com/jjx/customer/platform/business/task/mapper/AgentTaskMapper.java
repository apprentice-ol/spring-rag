package com.jjx.customer.platform.business.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jjx.customer.platform.business.task.TaskReaperStore;
import com.jjx.customer.platform.business.task.entity.AgentTaskEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Agent 任务 Mapper（MyBatis-Plus）。
 *
 * <p>状态字面量用枚举名（大写），与 {@code AgentTaskState.Status} 一一对应。</p>
 */
@Mapper
public interface AgentTaskMapper extends BaseMapper<AgentTaskEntity>, TaskReaperStore {

    /**
     * 抢占为进行中（恢复路径）：仅当行仍处于「可沿用」状态时成功。
     *
     * <p>并发双请求只有一个能改到这行（行锁 + WHERE 状态条件），另一个拿到 0 行。</p>
     *
     * @param taskId 任务标识
     * @return 影响行数（1 = 抢占成功）
     */
    @Update("UPDATE sa_agent_task SET status = 'RUNNING', update_time = NOW() "
            + "WHERE task_id = #{taskId} AND status IN ('OPEN', 'SUSPENDED', 'CONCLUDED')")
    int claim(String taskId);

    /**
     * 开启新 attempt：抢占 + 计数递增，返回新的 attempt 序号。
     *
     * <p>用一个 {@code UPDATE … RETURNING} 同时完成三件事——<b>并发互斥</b>（条件不满足即 0 行）、
     * <b>计数递增</b>、<b>状态推进</b>。旧实现靠'先查后写'，多实例下两个请求会同时 startSession
     * 各跑一份、互相覆盖。</p>
     *
     * <p>条件含 SUSPENDED：正常路径下挂起任务会先走恢复，走不到这里；能走到，说明恢复被拒
     * （引擎会话缺失——行被清理 / 跨版本迁移）。若把 SUSPENDED 排除在外，这类任务会
     * <b>永久卡死</b>：每次请求都落到"已有 attempt 在跑"的异常上，再也开不出新 attempt。</p>
     *
     * <p>放开 SUSPENDED 不影响并发安全：并发抢占时先到的那个已把状态改成 RUNNING，
     * 后到的自然匹配不到任何行。</p>
     *
     * @param taskId 任务标识
     * @return 新的 attempt 序号；抢占失败时返回 null
     */
    @Select("UPDATE sa_agent_task SET status = 'RUNNING', attempt_count = attempt_count + 1, "
            + "update_time = NOW() WHERE task_id = #{taskId} "
            + "AND status IN ('OPEN', 'CONCLUDED', 'SUSPENDED') "
            + "RETURNING attempt_count")
    Integer beginAttempt(String taskId);

    /**
     * 释放占位（幂等）：抢占后本轮若没跑起来，把任务放回可恢复态。
     *
     * <p>放回 {@code SUSPENDED} 而非 {@code OPEN}——抢占前它必然是可沿用状态，
     * 而 {@code SUSPENDED} 是其中最能保住"用户还有一轮没答完"语义的那个；
     * 若原本是 {@code CONCLUDED}，回到 {@code SUSPENDED} 只是让下一轮多带一次上下文，不会丢东西。</p>
     *
     * @param taskId 任务标识
     * @return 影响行数（0 = 未处于 RUNNING，无需释放）
     */
    @Update("UPDATE sa_agent_task SET status = 'SUSPENDED', update_time = NOW() "
            + "WHERE task_id = #{taskId} AND status = 'RUNNING'")
    int release(String taskId);

    /**
     * TTL 过期：超时未动的任务 → ABANDONED。
     *
     * <p>条件里**含 RUNNING**——进程被杀（部署重启 / OOM）会留下永远停在 RUNNING 的行：
     * {@code release} 只在异常路径执行，崩溃时不走。这类行既不会被恢复也不会被清理，
     * 且 {@code findActive} 见它会当成"有 attempt 在跑"。TTL 兜底是唯一的回收口径。</p>
     */
    @Update("UPDATE sa_agent_task SET status = 'ABANDONED', update_time = NOW(), close_time = NOW() "
            + "WHERE conversation_id = #{conversationId} "
            + "AND status IN ('OPEN', 'SUSPENDED', 'CONCLUDED', 'RUNNING') "
            + "AND update_time < NOW() - (#{ttlMinutes} || ' minutes')::interval")
    int expire(String conversationId, int ttlMinutes);

    /**
     * <b>全局</b>清扫过期任务（跨会话）——回收环节的兜底。
     *
     * <p>{@link #expire} 是惰性的：只在某个会话被再次访问时清它自己。于是<b>再也没人访问的会话</b>
     * 会永远留着行——尤其是进程被杀留下的 {@code RUNNING}（{@code release} 只在异常路径执行，
     * 崩溃时不走）。本方法不挑会话，按时间一刀切。</p>
     *
     * @param ttlMinutes 超时阈值（分钟）
     * @return 清扫行数
     */
    @Update("UPDATE sa_agent_task SET status = 'ABANDONED', update_time = NOW(), close_time = NOW() "
            + "WHERE status IN ('OPEN', 'RUNNING', 'SUSPENDED', 'CONCLUDED') "
            + "AND update_time < NOW() - (#{ttlMinutes} || ' minutes')::interval")
    int abandonStale(int ttlMinutes);

    /**
     * 终态且超过保留期的任务——它们的引擎执行态可回收。
     *
     * <p>只取 {@code CLOSED}/{@code ABANDONED}：{@code CONCLUDED} 是"等用户反应"，
     * 其引擎态随时可能被追问用上，不能回收。</p>
     *
     * @param retentionHours 保留时长（小时）
     * @return 可回收任务（仅需 taskId 与 attemptCount，用于推导引擎会话 id）
     */
    @Select("SELECT task_id, attempt_count FROM sa_agent_task "
            + "WHERE status IN ('CLOSED', 'ABANDONED') AND engine_reclaimed = 0 "
            + "AND update_time < NOW() - (#{retentionHours} || ' hours')::interval")
    java.util.List<AgentTaskEntity> findReclaimable(int retentionHours);

    /**
     * 标记引擎执行态已回收。
     *
     * <p>不标记的话，同一批终态任务每轮都会被 {@link #findReclaimable} 取出来再走一遍
     * "查无此行"的空删除——扫描量随历史无限增长。</p>
     *
     * @param taskId 任务标识
     * @return 影响行数
     */
    @Update("UPDATE sa_agent_task SET engine_reclaimed = 1 WHERE task_id = #{taskId}")
    int markEngineReclaimed(String taskId);

    /**
     * 孤儿引擎会话 id（详见 {@link TaskReaperStore#findOrphanEngineSessions}）。
     *
     * <p>「属于某任务」的判据是引擎会话 id 能被某个任务推导出来：{@code 'ops-' || task_id || '#' || n}，
     * n 取遍该任务的 attempt 序号。这个模式与 {@code AgentTaskState.attemptIdOf} 是一回事——
     * <b>改派生规则时两处必须同步</b>。</p>
     */
    @Select("SELECT e.session_id FROM ops_engine_session e "
            + "WHERE e.updated_at < NOW() - (#{retentionHours} || ' hours')::interval "
            + "AND NOT EXISTS (SELECT 1 FROM sa_agent_task t, "
            + "generate_series(1, t.attempt_count) g "
            + "WHERE e.session_id = 'ops-' || t.task_id || '#' || g) "
            + "LIMIT #{batchSize}")
    java.util.List<String> findOrphanEngineSessions(int retentionHours, int batchSize);
}
