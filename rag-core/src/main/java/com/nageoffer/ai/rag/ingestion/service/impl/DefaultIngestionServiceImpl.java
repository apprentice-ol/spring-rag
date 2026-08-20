package com.nageoffer.ai.rag.ingestion.service.impl;

import com.nageoffer.ai.rag.ingestion.engine.fetcher.DocumentSource;
import com.nageoffer.ai.rag.ingestion.engine.enums.SourceType;
import com.nageoffer.ai.rag.ingestion.service.IngestionEngineService;
import com.nageoffer.ai.rag.ingestion.service.IngestionService;
import com.nageoffer.ai.rag.ingestion.service.IngestionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 入库服务（把字节 + 来源交给 {@link IngestionEngineService}，由节点引擎编排）。
 *
 * <p><b>刻意不加 @Transactional</b>：链路里的写操作（IndexerNode 多批 INSERT 本就无整体事务、
 * sa_document 单条 insert、S3 上传非事务、节点日志异步落库早已逃逸事务）之间没有原子性需求，
 * 而事务会把 MinerU 轮询（最长 300s+）/ Enricher LLM / S3 上传全部圈进同一个 DB 连接，
 * MQ 并发消费时直接耗尽连接池。</p>
 */
@Service
@RequiredArgsConstructor
public class DefaultIngestionServiceImpl implements IngestionService {

    private final IngestionEngineService engineService;

    /** 默认 pipeline（P2 从 props.getDefaultPipelineId() 读） */
    private static final String DEFAULT_PIPELINE = "default";

    @Override
    public IngestionResult ingest(byte[] bytes, String filename, String mimeType,
                                  Long collectionId, Boolean plainText, String docId) {
        DocumentSource source = DocumentSource.builder()
                .type(SourceType.FILE)
                .location(filename)
                .fileName(filename)
                .build();
        return engineService.executeTask(DEFAULT_PIPELINE, source, bytes, mimeType, collectionId, plainText, docId);
    }
}
