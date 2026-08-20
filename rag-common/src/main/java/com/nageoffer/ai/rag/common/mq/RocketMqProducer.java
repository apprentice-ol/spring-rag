package com.nageoffer.ai.rag.common.mq;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * RocketMQ 实现的 {@link MqProducer}。
 * <p>
 * 通过 {@code rag.mq.type=rocketmq}（默认）启用，后续可替换为 Kafka / EventBus 等实现。
 * 使用 {@link MessageWrapper} 统一包装业务载荷，支持普通消息和事务消息两种模式。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.mq.type", havingValue = "rocketmq", matchIfMissing = true)
public class RocketMqProducer implements MqProducer {

    /** RocketMQ 操作模板 */
    private final RocketMQTemplate rocketMQTemplate;

    /** 事务消息监听器（处理 half 消息 → 本地事务 → commit/rollback） */
    private final DelegatingTransactionListener transactionListener;

    /**
     * 同步发送普通消息。
     * <p>自动用 {@link MessageWrapper} 包装业务载荷，并设置业务 keys。</p>
     *
     * @param topic   目标 topic
     * @param keys    业务 key，为空则自动生成 UUID
     * @param bizDesc 业务描述，仅用于日志
     * @param body    业务载荷
     * @return RocketMQ 发送结果
     */
    @Override
    public SendResult send(String topic, String keys, String bizDesc, Object body) {
        keys = StrUtil.isEmpty(keys) ? UUID.randomUUID().toString() : keys;

        Message<MessageWrapper<Object>> message = MessageBuilder
                .withPayload(MessageWrapper.builder().keys(keys).body(body).build())
                .setHeader(MessageConst.PROPERTY_KEYS, keys)
                .build();

        SendResult sendResult;
        try {
            sendResult = rocketMQTemplate.syncSend(topic, message);
        } catch (Throwable ex) {
            log.error("[MQ] {} - 发送失败, topic={}, keys={}", bizDesc, topic, keys, ex);
            throw ex;
        }

        log.info("[MQ] {} - 发送成功, topic={}, keys={}, msgId={}, status={}",
                bizDesc, topic, keys, sendResult.getMsgId(), sendResult.getSendStatus());
        return sendResult;
    }

    /**
     * 发送事务消息。
     * <p>
     * 流程：发送 half 消息 → RocketMQ 返回 → 执行业务方传入的 {@code localTransaction} →
     * 根据执行结果 commit 或 rollback 消息。
     * 若 Broker 长时间未收到确认，会触发按 topic 注册的 {@link TransactionChecker} 进行回查。
     * </p>
     *
     * @param topic            目标 topic
     * @param keys             业务 key
     * @param bizDesc          业务描述，仅用于日志
     * @param body             业务载荷
     * @param localTransaction 本地事务逻辑（half 消息发送成功后执行，抛异常则回滚消息）
     */
    @Override
    public void sendInTransaction(String topic, String keys, String bizDesc, Object body,
                                  Consumer<Object> localTransaction) {
        keys = StrUtil.isEmpty(keys) ? UUID.randomUUID().toString() : keys;
        String txId = UUID.randomUUID().toString();

        transactionListener.registerLocalTransaction(txId, localTransaction);

        Message<MessageWrapper<Object>> message = MessageBuilder
                .withPayload(MessageWrapper.builder().keys(keys).body(body).build())
                .setHeader(MessageConst.PROPERTY_KEYS, keys)
                .setHeader(DelegatingTransactionListener.HEADER_TX_ID, txId)
                .setHeader(DelegatingTransactionListener.HEADER_TOPIC, topic)
                .build();

        try {
            rocketMQTemplate.sendMessageInTransaction(topic, message, null);
            log.info("[MQ] {} - 事务消息发送成功, topic={}, keys={}", bizDesc, topic, keys);
        } catch (Throwable ex) {
            // half 消息发送失败：本地事务回调永远不会被触发，注销注册避免 Map 无界增长
            transactionListener.unregisterLocalTransaction(txId);
            log.error("[MQ] {} - 事务消息发送失败, topic={}, keys={}", bizDesc, topic, keys, ex);
            throw ex;
        }
    }
}
