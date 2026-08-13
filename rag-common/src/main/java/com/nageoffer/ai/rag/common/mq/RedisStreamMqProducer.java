package com.nageoffer.ai.rag.common.mq;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 基于 Redis Streams 的 {@link MqProducer} 实现。
 * <p>
 * 通过 {@code rag.mq.type=redis} 启用，作为 RocketMQ 的轻量替代（面向 2核4G 部署）。
 * 消息以 {@link MessageWrapper} 包装后 JSON 序列化，{@code XADD} 到 {@link MqProperties#getStreamName()}；
 * 消费由业务模块的 Redis Stream 消费者（如 RedisStreamIngestionConsumer）用 {@code XREADGROUP} 拉取。
 * </p>
 *
 * <p><b>不支持事务消息</b>：Redis Streams 无半消息机制，{@link #sendInTransaction} 直接抛异常。
 * 业务上无调用方（事务消息仅为脚手架），不影响功能。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.mq.type", havingValue = "redis")
@ConditionalOnClass(StringRedisTemplate.class)
public class RedisStreamMqProducer implements MqProducer {

    /** Redis Stream 操作（由 spring-boot-starter-data-redis 自动配置） */
    private final StringRedisTemplate stringRedisTemplate;

    /** JSON 序列化（由 Spring Boot 自动注入） */
    private final ObjectMapper objectMapper;

    /** MQ 配置：读取 stream key */
    private final MqProperties mqProperties;

    /**
     * XADD 发送消息。
     * <p>消息体 {@code body} 用 {@link MessageWrapper} 包装 → JSON → 单字段 {@code payload}。</p>
     *
     * @param topic 仅作日志参考，实际 stream key 取自 {@link MqProperties#getStreamName()}
     * @return 固定返回 {@code null}（接口签名要求 {@link SendResult}，仅 RocketMQ 实现有意义；
     *         当前调用方 IngestionProducer 不使用返回值）
     */
    @Override
    public SendResult send(String topic, String keys, String bizDesc, Object body) {
        keys = StrUtil.isEmpty(keys) ? UUID.randomUUID().toString() : keys;
        String streamKey = mqProperties.getStreamName();

        try {
            MessageWrapper<Object> wrapper = MessageWrapper.builder().keys(keys).body(body).build();
            String json = objectMapper.writeValueAsString(wrapper);

            Map<String, String> payload = Collections.singletonMap("payload", json);
            RecordId recordId = stringRedisTemplate.opsForStream()
                    .add(StreamRecords.string(payload).withStreamKey(streamKey));

            log.info("[MQ-Redis] {} - XADD 成功, stream={}, keys={}, id={}",
                    bizDesc, streamKey, keys, recordId.getValue());
        } catch (JsonProcessingException ex) {
            log.error("[MQ-Redis] {} - 消息序列化失败, stream={}, keys={}", bizDesc, streamKey, keys, ex);
            throw new RuntimeException("Redis Stream 消息序列化失败: " + ex.getMessage(), ex);
        } catch (Exception ex) {
            log.error("[MQ-Redis] {} - XADD 失败, stream={}, keys={}", bizDesc, streamKey, keys, ex);
            throw new RuntimeException("Redis Stream 发送失败: " + ex.getMessage(), ex);
        }
        return null;
    }

    /**
     * Redis Streams 不支持事务消息。
     *
     * @throws UnsupportedOperationException 始终抛出；如需事务消息请用 {@code rag.mq.type=rocketmq}
     */
    @Override
    public void sendInTransaction(String topic, String keys, String bizDesc, Object body,
                                  Consumer<Object> localTransaction) {
        log.warn("[MQ-Redis] sendInTransaction 不支持（Redis Streams 无事务消息语义），topic={}, keys={}", topic, keys);
        throw new UnsupportedOperationException(
                "Redis Streams 实现不支持事务消息，请使用 rag.mq.type=rocketmq");
    }
}
