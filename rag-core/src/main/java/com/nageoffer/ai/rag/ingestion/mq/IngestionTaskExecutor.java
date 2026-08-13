package com.nageoffer.ai.rag.ingestion.mq;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.nageoffer.ai.rag.common.mq.MessageWrapper;
import com.nageoffer.ai.rag.ingestion.domain.entity.IngestionTaskEntity;
import com.nageoffer.ai.rag.ingestion.mapper.IngestionTaskMapper;
import com.nageoffer.ai.rag.ingestion.service.IngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

/**
 * 入库任务执行器（共享 ETL 逻辑）。
 * <p>
 * 把「更新状态 PROCESSING → 调 {@link IngestionService#ingest} → DONE/FAILED」抽离，
 * 供 RocketMQ 消费者（{@link IngestionConsumer}）与 Redis Stream 消费者（RedisStreamIngestionConsumer）复用，
 * 保证两种 MQ 实现的入库语义完全一致：失败回写 FAILED 且不抛异常（即"消费成功不重投"）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IngestionTaskExecutor {

    /** 入库服务，复用同步入库的 ETL 逻辑 */
    private final IngestionService ingestionService;

    /** 入库任务 Mapper，用于更新任务状态 */
    private final IngestionTaskMapper taskMapper;

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

        updateStatus(msg.getTaskId(), "PROCESSING", null);
        try {
            FileSystemResource resource = new FileSystemResource(msg.getFilePath());
            ingestionService.ingest(resource, msg.getFilename(), msg.getMimeType(), msg.getCollectionId(), null);
            updateStatus(msg.getTaskId(), "DONE", null);
            log.info("[IngestionTaskExecutor] 入库完成: taskId={}", msg.getTaskId());
        } catch (Exception e) {
            log.error("[IngestionTaskExecutor] 入库失败: taskId={}", msg.getTaskId(), e);
            updateStatus(msg.getTaskId(), "FAILED", e.getMessage());
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
