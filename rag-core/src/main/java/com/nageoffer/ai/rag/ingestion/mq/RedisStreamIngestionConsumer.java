package com.nageoffer.ai.rag.ingestion.mq;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.rag.common.mq.MessageWrapper;
import com.nageoffer.ai.rag.common.mq.MqProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 异步入库消费者（Redis Streams 实现）。
 * <p>
 * 仅在 {@code rag.mq.type=redis} 时装配，作为 {@link IngestionConsumer}（RocketMQ）的轻量替代。
 * 启动时确保 stream 与 consumer group 存在，起虚拟线程长轮询（{@code XREADGROUP}）拉取消息，
 * 反序列化为 {@link MessageWrapper}<{@link IngestionMessage}> 后委托 {@link IngestionTaskExecutor} 执行，
 * 处理完成（无论成败）即 {@code XACK}，与 RocketMQ 版「失败不重投」语义一致。
 * </p>
 *
 * <p><b>已知限制</b>：应用重启后，PEL（pending list）内未 ack 的消息不会被自动补消费
 * （入库失败已回写 FAILED，业务上可接受）。如需补消费可后续加 {@code XAUTOCLAIM} 逻辑。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.mq.type", havingValue = "redis")
public class RedisStreamIngestionConsumer {

    /** 占位消息：建组前 XADD 一条以触发 Redis 自动创建 stream（createGroup 不自动 MKSTREAM） */
    private static final String INIT_PAYLOAD = "__STREAM_INIT__";

    /**
     * 消费者名（实例唯一）：同 group 下不同 consumer 名各自拿到不同消息（负载分摊）。
     * 此前硬编码 "ingestion-1"，多实例共用同名 consumer 会互抢同一条消息、负载不均。
     */
    private static final String CONSUMER_NAME = buildConsumerName();

    private static String buildConsumerName() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown-host";
        }
        return host + "-" + ProcessHandle.current().pid();
    }

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final IngestionTaskExecutor ingestionTaskExecutor;
    private final MqProperties mqProperties;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;

    /** 启动消费者：建组 + 起虚拟线程消费循环。 */
    @PostConstruct
    public void start() {
        ensureGroup();
        running.set(true);
        worker = Thread.ofVirtual().name("redis-stream-ingestion").start(this::consumeLoop);
        log.info("[RedisStreamConsumer] 已启动, stream={}, group={}, consumer={}",
                mqProperties.getStreamName(), mqProperties.getConsumerGroup(), CONSUMER_NAME);
    }

    /** 停止消费者：置停止位 + 中断阻塞的 XREADGROUP。 */
    @PreDestroy
    public void stop() {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
        }
        log.info("[RedisStreamConsumer] 已停止");
    }

    /** 确保 consumer group 存在；stream 不存在时先 XADD 占位再建组。 */
    private void ensureGroup() {
        String stream = mqProperties.getStreamName();
        String group = mqProperties.getConsumerGroup();
        try {
            stringRedisTemplate.opsForStream().createGroup(stream, ReadOffset.from("0"), group);
            log.info("[RedisStreamConsumer] group 已创建: stream={}, group={}", stream, group);
        } catch (Exception ex) {
            // Redisson 把 BUSYGROUP/no such key 包装成 InvalidDataAccessApiUsageException（非 RedisSystemException），
            // 故用宽 Exception 捕获，并检查异常本身 + cause 的 message。
            String msg = ex.getMessage() != null ? ex.getMessage() : "";
            Throwable cause = ex.getCause();
            String causeMsg = cause != null && cause.getMessage() != null ? cause.getMessage() : "";
            if (msg.contains("BUSYGROUP") || causeMsg.contains("BUSYGROUP")) {
                log.info("[RedisStreamConsumer] group 已存在，跳过创建: {}", group);
            } else if (msg.contains("no such key") || causeMsg.contains("no such key")) {
                log.info("[RedisStreamConsumer] stream 不存在，先 XADD 占位再建组: {}", stream);
                stringRedisTemplate.opsForStream().add(stream, Map.of("payload", INIT_PAYLOAD));
                stringRedisTemplate.opsForStream().createGroup(stream, ReadOffset.from("0"), group);
            } else {
                throw ex;
            }
        }
    }

    /** 消费循环：长轮询 XREADGROUP，逐条处理。 */
    private void consumeLoop() {
        String stream = mqProperties.getStreamName();
        String group = mqProperties.getConsumerGroup();
        while (running.get()) {
            try {
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                        Consumer.from(group, CONSUMER_NAME),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(5)),
                        StreamOffset.create(stream, ReadOffset.lastConsumed()));
                if (records == null || records.isEmpty()) {
                    continue;
                }
                for (MapRecord<String, Object, Object> rec : records) {
                    processOne(rec);
                }
            } catch (Exception e) {
                // XREADGROUP 不声明 throws InterruptedException；中断/网络异常落到此，
                // 若 running 已为 false（关闭中）则直接退出，否则退避后重试。
                if (running.get()) {
                    log.error("[RedisStreamConsumer] 轮询异常, 3s 后重试", e);
                    sleepQuiet(3000);
                }
            }
        }
        log.info("[RedisStreamConsumer] 消费循环退出");
    }

    /** 处理单条消息：反序列化 → 委托 executor → ack。反序列化/异常消息也 ack，避免毒消息死循环。 */
    private void processOne(MapRecord<String, Object, Object> rec) {
        String id = rec.getId().getValue();
        try {
            Object payloadObj = rec.getValue().get("payload");
            String json = payloadObj == null ? null : payloadObj.toString();
            if (INIT_PAYLOAD.equals(json)) {
                ack(id);   // 建组占位消息，跳过
                return;
            }
            if (json == null || json.isBlank()) {
                log.warn("[RedisStreamConsumer] 消息无 payload，ack 跳过: id={}", id);
                ack(id);
                return;
            }
            MessageWrapper<IngestionMessage> wrapper = objectMapper.readValue(json,
                    new TypeReference<MessageWrapper<IngestionMessage>>() {});
            ingestionTaskExecutor.execute(wrapper);   // 内部已 catch 回写 FAILED
            ack(id);                                  // 成败均 ack，与 RocketMQ 版「失败不重投」一致
        } catch (Exception e) {
            log.error("[RedisStreamConsumer] 反序列化/处理失败，ack 并跳过（毒消息兜底）: id={}", id, e);
            ack(id);
        }
    }

    private void ack(String id) {
        try {
            stringRedisTemplate.opsForStream().acknowledge(
                    mqProperties.getStreamName(), mqProperties.getConsumerGroup(), id);
        } catch (Exception e) {
            log.warn("[RedisStreamConsumer] ack 失败: id={}", id, e);
        }
    }

    private void sleepQuiet(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
