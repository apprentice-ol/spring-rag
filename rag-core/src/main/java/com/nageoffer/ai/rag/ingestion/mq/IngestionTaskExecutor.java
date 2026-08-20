package com.nageoffer.ai.rag.ingestion.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.nageoffer.ai.rag.common.mq.MessageWrapper;
import com.nageoffer.ai.rag.ingestion.domain.entity.DocumentEntity;
import com.nageoffer.ai.rag.ingestion.domain.entity.IngestionTaskEntity;
import com.nageoffer.ai.rag.ingestion.mapper.DocumentMapper;
import com.nageoffer.ai.rag.ingestion.mapper.IngestionTaskMapper;
import com.nageoffer.ai.rag.ingestion.service.IngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;

/**
 * 入库任务执行器（共享 ETL 逻辑）。
 * <p>
 * 把「CAS 抢占任务 → 调 {@link IngestionService#ingest} → DONE/FAILED」抽离，
 * 供 RocketMQ 消费者（{@link IngestionConsumer}）与 Redis Stream 消费者（RedisStreamIngestionConsumer）复用，
 * 保证两种 MQ 实现的入库语义完全一致：失败回写 FAILED 且不抛异常（即"消费成功不重投"）。
 * </p>
 *
 * <p><b>幂等</b>：MQ 是 at-least-once，重投/重启会重放同一消息。入口按状态机 CAS 抢占
 * （仅 PENDING/FAILED 可进 PROCESSING）+ docId 已存在即跳过，配合引擎侧同 docId 先清残留向量，
 * 重放不会产生重复文档与重复向量。PROCESSING 不允许重抢：信号量排队会拉长消费耗时，
 * 超过 consumeTimeout 的重投若再抢占会同一任务并发跑两遍。</p>
 *
 * <p><b>限流</b>：执行并发用信号量收口到 {@link #EXECUTION_CONCURRENCY}（与 MinerU 信号量、
 * DB 连接池容量对齐）。超出的消费线程在此<b>排队等待</b>——此前靠 RocketMQ 消费线程数自然限流，
 * 多出的任务在 MinerU 层 tryAcquire(30s) 超时后直接 FAILED，而非排队。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IngestionTaskExecutor {

    /** 实际执行并发上限：对齐 MinerU 解析信号量（mineru.concurrency-limit=5）与下游 DB/LLM 容量 */
    private static final int EXECUTION_CONCURRENCY = 5;

    /** 入库服务，复用同步入库的 ETL 逻辑 */
    private final IngestionService ingestionService;

    /** 入库任务 Mapper，用于更新任务状态 */
    private final IngestionTaskMapper taskMapper;

    /** 文档 Mapper，用于 docId 幂等检查 */
    private final DocumentMapper documentMapper;

    /** 执行闸门：消费线程（默认 20）在此排队，实际并发恒 ≤ EXECUTION_CONCURRENCY */
    private final Semaphore executionSlots = new Semaphore(EXECUTION_CONCURRENCY);

    /**
     * 执行单条入库任务。
     * <p>失败时回写 FAILED + errorMsg，<b>不抛异常</b>（由调用方决定 ack/重投策略）。</p>
     *
     * @param wrapper 消息包装，通过 {@link MessageWrapper#getBody()} 获取 {@link IngestionMessage}
     */
    public void execute(MessageWrapper<IngestionMessage> wrapper) {
        IngestionMessage msg = wrapper.getBody();
        log.info("[IngestionTaskExecutor] 收到入库任务: taskId={}, docId={}, keys={}",
                msg.getTaskId(), msg.getDocId(), wrapper.getKeys());

        // CAS 抢占：仅 PENDING/FAILED 可进入 PROCESSING。
        // 不含 PROCESSING：信号量排队会拉长消费耗时，超过 consumeTimeout 的重投若重抢 PROCESSING
        // 任务，会同一任务并发跑两遍（引擎侧幂等 DELETE 只防先后，不防同时）。残留的 PROCESSING
        // （进程宕机未回写）依赖 docId 检查兜底或人工重置。
        IngestionTaskEntity claim = new IngestionTaskEntity();
        claim.setStatus("PROCESSING");
        claim.setErrorMsg(null);
        int claimed = taskMapper.update(claim, new LambdaUpdateWrapper<IngestionTaskEntity>()
                .eq(IngestionTaskEntity::getTaskId, msg.getTaskId())
                .notIn(IngestionTaskEntity::getStatus, "PROCESSING", "DONE"));
        if (claimed == 0) {
            log.info("[IngestionTaskExecutor] 任务已完成或正在处理，跳过重投: taskId={}", msg.getTaskId());
            return;
        }

        // 上次执行已完成落库但状态未回写（进程宕机）时，按 docId 已存在跳过重复入库
        Long existingDocs = documentMapper.selectCount(new LambdaQueryWrapper<DocumentEntity>()
                .eq(DocumentEntity::getDocId, msg.getDocId()));
        if (existingDocs != null && existingDocs > 0) {
            updateStatus(msg.getTaskId(), "DONE", null);
            log.info("[IngestionTaskExecutor] docId 已存在文档，回写 DONE 并跳过: taskId={}, docId={}",
                    msg.getTaskId(), msg.getDocId());
            return;
        }

        // 排队等执行位（不可中断：中断会让消费线程与信号量计数错乱）；单任务分钟级，队尾最长排队 = (消费线程数-并发) × 单任务时长
        executionSlots.acquireUninterruptibly();
        try {
            byte[] bytes = Files.readAllBytes(Path.of(msg.getFilePath()));
            // docId 必须透传：引擎按它做幂等清理，且与 /upload-async 返回给前端的 docId 对齐
            ingestionService.ingest(bytes, msg.getFilename(), msg.getMimeType(),
                    msg.getCollectionId(), null, msg.getDocId());
            updateStatus(msg.getTaskId(), "DONE", null);
            log.info("[IngestionTaskExecutor] 入库完成: taskId={}, docId={}", msg.getTaskId(), msg.getDocId());
        } catch (Exception e) {
            log.error("[IngestionTaskExecutor] 入库失败: taskId={}", msg.getTaskId(), e);
            updateStatus(msg.getTaskId(), "FAILED", e.getMessage());
        } finally {
            executionSlots.release();
            // 消费完清理本地临时文件（此前按任务数无限累积）
            try {
                Files.deleteIfExists(Path.of(msg.getFilePath()));
            } catch (IOException e) {
                log.warn("[IngestionTaskExecutor] 临时文件清理失败: path={}, {}", msg.getFilePath(), e.getMessage());
            }
        }
    }

    /**
     * 更新入库任务状态。
     *
     * @param taskId   任务 ID
     * @param status   目标状态（PROCESSING / DONE / FAILED）
     * @param errorMsg 错误信息（仅 FAILED 时非空）
     */
    private void updateStatus(String taskId, String status, String errorMsg) {
        IngestionTaskEntity update = new IngestionTaskEntity();
        update.setStatus(status);
        update.setErrorMsg(errorMsg);
        taskMapper.update(update, new LambdaUpdateWrapper<IngestionTaskEntity>()
                .eq(IngestionTaskEntity::getTaskId, taskId));
    }
}
