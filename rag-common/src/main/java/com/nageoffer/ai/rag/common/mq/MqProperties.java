package com.nageoffer.ai.rag.common.mq;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MQ 相关配置。
 * <p>
 * 通过 {@code rag.mq.type} 切换 MQ 实现：
 * <ul>
 *   <li>{@code rocketmq}（默认）— {@link RocketMqProducer} + {@code @RocketMQMessageListener} 消费</li>
 *   <li>{@code redis} — {@link RedisStreamMqProducer} + Redis Stream 消费</li>
 * </ul>
 * topic 与 consumer-group 统一从此处读取，消除原硬编码。
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.mq")
public class MqProperties {

    /** MQ 类型：rocketmq（默认） / redis */
    private String type = "rocketmq";

    /** 业务 topic（同时作 RocketMQ topic 与 Redis stream 默认名） */
    private String topic = "ingestion";

    /** 消费组名 */
    private String consumerGroup = "ingestion-consumer";

    /** Redis stream key（默认同 topic） */
    private String streamName = "ingestion";
}
