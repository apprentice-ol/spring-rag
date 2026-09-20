package com.jjx.customer.platform.business.task;

import com.jjx.customer.platform.business.task.entity.AgentTaskEntity;
import java.util.List;

/**
 * 回收环节对任务存储的窄契约。
 *
 * <p>单独抽出来的唯一理由是<b>可测</b>：{@link com.jjx.customer.platform.business.task.mapper.AgentTaskMapper}
 * 继承 MyBatis-Plus 的 {@code BaseMapper}（二十多个方法），手写替身不现实；而回收逻辑
 * （扫描 → 遍历 attempt → 删引擎态 → 标记）恰恰是需要验证的部分。</p>
 *
 * <p>{@code AgentTaskMapper} 继承本接口，回收器只依赖这一面——实现上零额外代码。</p>
 *
 * <p><b>本接口刻意不放在 {@code *.mapper} 包</b>：{@code @MapperScan("…**.mapper")}
 * 会把该包下每个接口都注册成 MyBatis mapper，放进去会让本接口凭空多出一个实现 bean，
 * 注入时报 "required a single bean, but 2 were found"。它是<b>端口</b>，不是 mapper。</p>
 */
public interface TaskReaperStore {

    /**
     * 全局清扫过期任务（跨会话）。
     *
     * @param ttlMinutes 超时阈值（分钟）
     * @return 清扫行数
     */
    int abandonStale(int ttlMinutes);

    /**
     * 终态且超过保留期、尚未回收的任务。
     *
     * @param retentionHours 保留时长（小时）
     * @return 可回收任务
     */
    List<AgentTaskEntity> findReclaimable(int retentionHours);

    /**
     * 标记引擎执行态已回收。
     *
     * @param taskId 任务标识
     * @return 影响行数
     */
    int markEngineReclaimed(String taskId);

    /**
     * 孤儿引擎会话：不属于任何任务、且超过保留期的会话 id。
     *
     * <p><b>为什么需要单独扫</b>：按 Task 反推的回收（{@link #findReclaimable}）**永远找不到它们**——
     * 它们不挂在任何 Task 上。来源是两类历史遗留：{@code ephemeral} 未生效时每次知识问答 /
     * REST 单轮生成的随机 UUID，以及更早的 {@code ops-<conversationId>} 格式。</p>
     *
     * <p><b>为什么这样判安全</b>：保留期内的会话不动；而超过保留期仍活着的任务，
     * 早被 {@link #abandonStale}（TTL 60 分钟）判成终态了。所以这里删掉的，
     * 要么属于已终止的任务，要么根本无主。</p>
     *
     * @param retentionHours 保留时长（小时）
     * @param batchSize      单批上限（避免一次删太多撑爆事务）
     * @return 孤儿会话 id
     */
    List<String> findOrphanEngineSessions(int retentionHours, int batchSize);
}
