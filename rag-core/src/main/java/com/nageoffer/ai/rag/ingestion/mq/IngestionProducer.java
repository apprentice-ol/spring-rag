package com.nageoffer.ai.rag.ingestion.mq;

import com.nageoffer.ai.rag.common.mq.MqProducer;
import com.nageoffer.ai.rag.common.mq.MqProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 入库消息生产者。
 * <p>
 * 对 {@link MqProducer} 的模块级包装，业务层只需调用 {@link #send(IngestionMessage)} 或
 * {@link #sendInTransaction(IngestionMessage, Runnable)}，不接触 topic 和 RocketMQ 细节。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IngestionProducer {

    /** 通用 MQ 生产者（由 common 模块注入，rocketmq/redis 实现按 rag.mq.type 切换） */
    private final MqProducer mqProducer;

    /** MQ 配置（读取统一 topic，替代 IngestionTopic 硬编码常量） */
    private final MqProperties mqProperties;

    /**
     * 同步发送入库消息。
     * <p>
     * 使用消息的 {@link IngestionMessage#getTaskId()} 作为业务 keys，便于消费者幂等处理。
     * </p>
     *
     * @param msg 入库消息体
     */
    public void send(IngestionMessage msg) {
        mqProducer.send(mqProperties.getTopic(), msg.getTaskId(), "入库", msg);
        log.info("[IngestionProducer] 发送入库任务: taskId={}, docId={}", msg.getTaskId(), msg.getDocId());
    }

    /**
     * 事务消息发送入库消息（示例）。
     * <p>
     * 先发 half 消息到 RocketMQ，执行业务方传入的 {@code localTransaction} 本地事务，
     * 成功后 commit 消息，失败则 rollback。若 Broker 回查，由 {@link IngestionTransactionChecker} 处理。
     * </p>
     *
     * @param msg              入库消息体
     * @param localTransaction 本地事务逻辑（如写 DB）
     */
    public void sendInTransaction(IngestionMessage msg, Runnable localTransaction) {
        mqProducer.sendInTransaction(mqProperties.getTopic(), msg.getTaskId(), "入库-事务", msg,
                arg -> localTransaction.run());
        log.info("[IngestionProducer] 事务消息已发送: taskId={}", msg.getTaskId());
    }
}
