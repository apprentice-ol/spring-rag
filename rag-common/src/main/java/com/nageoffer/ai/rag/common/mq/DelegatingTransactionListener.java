package com.nageoffer.ai.rag.common.mq;

import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.Message;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * 通用的 RocketMQ 事务消息监听器。
 * <p>
 * 自动注册为 RocketMQ 事务监听器，管理两条路由：
 * <ul>
 *   <li><b>本地事务执行</b> — per-message，仅当前实例有效，由 {@link RocketMqProducer#sendInTransaction} 注册</li>
 *   <li><b>事务回查</b> — per-topic，由各模块的 {@link TransactionChecker} 实现，
 *       通过 {@link #registerChecker} 注册，可跨实例</li>
 * </ul>
 * </p>
 */
@Slf4j
@RocketMQTransactionListener
@ConditionalOnProperty(name = "rag.mq.type", havingValue = "rocketmq", matchIfMissing = true)
public class DelegatingTransactionListener implements RocketMQLocalTransactionListener {

    /** 自定义 Header：本地事务上下文 ID */
    static final String HEADER_TX_ID = "TRANSACTION_CONTEXT_ID";

    /** 自定义 Header：回查时识别 topic */
    static final String HEADER_TOPIC = "TRANSACTION_TOPIC";

    /** 本地事务执行逻辑，per-message，仅当前实例有效 */
    private final ConcurrentMap<String, Consumer<Object>> localTransactionMap = new ConcurrentHashMap<>();

    /** 事务回查逻辑，per-topic，所有实例共享（Spring Bean 注册） */
    private final ConcurrentMap<String, TransactionChecker> checkerMap = new ConcurrentHashMap<>();

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * 注册本地事务逻辑（由 {@link RocketMqProducer#sendInTransaction} 调用）。
     *
     * @param txId             事务 ID
     * @param localTransaction 本地事务回调
     */
    public void registerLocalTransaction(String txId, Consumer<Object> localTransaction) {
        localTransactionMap.put(txId, localTransaction);
    }

    /**
     * 注册 topic 对应的事务回查器（由各模块的 {@link TransactionChecker} 在 {@code @PostConstruct} 中调用）。
     *
     * @param topic   topic 名称
     * @param checker 回查器实现
     */
    public void registerChecker(String topic, TransactionChecker checker) {
        checkerMap.put(topic, checker);
    }

    /**
     * 执行本地事务（half 消息发送成功后由 RocketMQ 回调）。
     * <p>
     * 从事务上下文中取出业务方注册的本地事务逻辑，在 Spring 事务管理中执行。
     * 执行成功 → COMMIT，失败 → ROLLBACK。
     * </p>
     */
    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message message, Object arg) {
        String txId = (String) message.getHeaders().get(HEADER_TX_ID);
        Consumer<Object> localTransaction = txId != null ? localTransactionMap.remove(txId) : null;
        if (localTransaction == null) {
            log.error("[事务消息] 未找到本地事务逻辑, txId={}", txId);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> localTransaction.accept(arg));
            return RocketMQLocalTransactionState.COMMIT;
        } catch (Exception e) {
            log.error("[事务消息] 本地事务执行失败, txId={}", txId, e);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    /**
     * 回查本地事务状态（Broker 未收到 commit/rollback 时回调）。
     * <p>
     * 按 topic 路由到对应的 {@link TransactionChecker}，通过查询 DB 确认事务是否已提交。
     * </p>
     */
    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message message) {
        String topic = (String) message.getHeaders().get(HEADER_TOPIC);
        TransactionChecker checker = topic != null ? checkerMap.get(topic) : null;
        if (checker == null) {
            log.warn("[事务消息] 回查时未找到 topic={} 对应的 checker, 默认 ROLLBACK", topic);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
        try {
            MessageWrapper<?> wrapper = (MessageWrapper<?>) message.getPayload();
            boolean committed = checker.check(wrapper);
            RocketMQLocalTransactionState state = committed
                    ? RocketMQLocalTransactionState.COMMIT
                    : RocketMQLocalTransactionState.ROLLBACK;
            log.info("[事务消息] 回查结果: topic={}, state={}", topic, state);
            return state;
        } catch (Exception e) {
            log.error("[事务消息] 回查异常, topic={}", topic, e);
            return RocketMQLocalTransactionState.UNKNOWN;
        }
    }
}
