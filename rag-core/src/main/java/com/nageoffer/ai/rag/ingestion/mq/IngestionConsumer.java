package com.nageoffer.ai.rag.ingestion.mq;

import com.nageoffer.ai.rag.common.mq.MessageWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 异步入库消费者（RocketMQ 实现）。
 * <p>
 * 绑定 topic={@code ingestion}，收到 {@link MessageWrapper}<{@link IngestionMessage}> 后
 * 委托 {@link IngestionTaskExecutor} 执行 ETL。消费模式为 {@link ConsumeMode#CONCURRENTLY}。
 * </p>
 * <p>仅在 {@code rag.mq.type=rocketmq}（默认）时装配；{@code redis} 模式由 RedisStreamIngestionConsumer 接管。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.mq.type", havingValue = "rocketmq", matchIfMissing = true)
@RocketMQMessageListener(
        topic = "${rag.mq.topic:ingestion}",
        consumerGroup = "${rag.mq.consumer-group:ingestion-consumer}",
        consumeMode = ConsumeMode.CONCURRENTLY)
public class IngestionConsumer implements RocketMQListener<MessageWrapper<IngestionMessage>> {

    /** 入库执行器（共享 ETL 逻辑，与 Redis Stream 消费者复用同一份逻辑） */
    private final IngestionTaskExecutor ingestionTaskExecutor;

    /**
     * 消费入库消息：委托 {@link IngestionTaskExecutor#execute} 执行。
     * <p>执行器内部已处理 DONE/FAILED 状态回写且不抛异常（消费成功不重投）。</p>
     *
     * @param wrapper 消息包装，通过 {@link MessageWrapper#getBody()} 获取 {@link IngestionMessage}
     */
    @Override
    public void onMessage(MessageWrapper<IngestionMessage> wrapper) {
        ingestionTaskExecutor.execute(wrapper);
    }
}
