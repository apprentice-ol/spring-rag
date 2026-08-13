package com.nageoffer.ai.rag.ingestion.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nageoffer.ai.rag.common.mq.DelegatingTransactionListener;
import com.nageoffer.ai.rag.common.mq.MessageWrapper;
import com.nageoffer.ai.rag.common.mq.TransactionChecker;
import com.nageoffer.ai.rag.ingestion.domain.entity.DocumentEntity;
import com.nageoffer.ai.rag.ingestion.mapper.DocumentMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 入库事务消息回查器（示例）。
 * <p>
 * 当 {@link IngestionProducer#sendInTransaction} 发送事务消息后，
 * RocketMQ Broker 在长时间未收到 commit/rollback 时会触发回查。
 * 回查可能路由到任意实例，因此通过查询 DB 中文档是否存在来判断事务是否已提交。
 * </p>
 *
 * <b>使用场景</b>：上传文件 + 入库操作需要原子性时使用。例如：
 * <pre>{@code
 * // Controller 中
 * IngestionMessage msg = new IngestionMessage(taskId, docId, path, filename, mimeType);
 * ingestionProducer.sendInTransaction(msg, () -> {
 *     // 本地事务：保存文档记录到 DB
 *     documentMapper.insert(doc);
 * });
 * }</pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.mq.type", havingValue = "rocketmq", matchIfMissing = true)
public class IngestionTransactionChecker implements TransactionChecker {

    /** 文档 Mapper，用于回查时确认文档是否已入库 */
    private final DocumentMapper documentMapper;

    /** 事务监听器，用于注册当前 checker 到对应 topic */
    private final DelegatingTransactionListener transactionListener;

    /**
     * 初始化：向 {@link DelegatingTransactionListener} 注册本 checker。
     * <p>topic 取自 {@link IngestionTopic#INGESTION}。</p>
     */
    @PostConstruct
    public void init() {
        transactionListener.registerChecker(IngestionTopic.INGESTION, this);
        log.info("[IngestionTransactionChecker] 已注册 topic={}", IngestionTopic.INGESTION);
    }

    /**
     * 回查本地事务是否已提交。
     * <p>
     * 通过查询 {@code sa_document} 表确认文档记录是否存在来判断本地事务是否已完成。
     * 回查可能发生在任何实例上，因此必须查询 DB 而非内存状态。
     * </p>
     *
     * @param message 消息体，含 {@link IngestionMessage} 载荷
     * @return true 本地事务已提交（消息可投递），false 已回滚（消息丢弃）
     */
    @Override
    public boolean check(MessageWrapper<?> message) {
        IngestionMessage event = (IngestionMessage) message.getBody();

        // 查询 DB：如果文档已存在说明本地事务已提交
        DocumentEntity doc = documentMapper.selectOne(
                new LambdaQueryWrapper<DocumentEntity>()
                        .eq(DocumentEntity::getDocId, event.getDocId()));
        boolean committed = doc != null;

        log.info("[IngestionTransactionChecker] 回查结果: docId={}, committed={}",
                event.getDocId(), committed);
        return committed;
    }
}
