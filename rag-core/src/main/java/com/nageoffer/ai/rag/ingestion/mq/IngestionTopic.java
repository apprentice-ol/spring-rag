package com.nageoffer.ai.rag.ingestion.mq;

/**
 * 入库模块 MQ 主题定义。
 * <p>
 * 各业务模块自行管理 topic 常量，避免与其它模块冲突。
 * topic 值需与 application.yaml 中 RocketMQ 配置配合。
 * </p>
 *
 * @see com.nageoffer.ai.rag.common.mq.MqProducer
 */
public final class IngestionTopic {

    private IngestionTopic() {
    }

    /** 异步入库 topic */
    public static final String INGESTION = "ingestion";
}
