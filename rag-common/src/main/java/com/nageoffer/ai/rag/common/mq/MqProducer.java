package com.nageoffer.ai.rag.common.mq;

import org.apache.rocketmq.client.producer.SendResult;

import java.util.function.Consumer;

/**
 * MQ 生产者接口。
 * <p>
 * 各业务模块注入此接口发送消息，不直接依赖具体的 MQ 实现。
 * 当前默认实现为 {@link RocketMqProducer}。
 * </p>
 */
public interface MqProducer {

    /**
     * 同步发送消息。
     *
     * @param topic   目标 topic
     * @param keys    业务 key，用于幂等
     * @param bizDesc 业务描述，日志用
     * @param body    业务载荷
     * @return 发送结果
     */
    SendResult send(String topic, String keys, String bizDesc, Object body);

    /**
     * 发送事务消息。
     * <p>
     * 流程：发送 half 消息 → 执行本地事务 → 根据结果 commit/rollback。
     * 事务回查按 topic 注册的 {@link TransactionChecker} 处理。
     * </p>
     *
     * @param topic            目标 topic
     * @param keys             业务 key
     * @param bizDesc          业务描述
     * @param body             业务载荷
     * @param localTransaction 本地事务逻辑，half 成功后执行；抛异常则回滚消息
     */
    void sendInTransaction(String topic, String keys, String bizDesc, Object body,
                           Consumer<Object> localTransaction);
}
