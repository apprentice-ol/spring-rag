package com.nageoffer.ai.rag.ingestion.service.impl;

import com.nageoffer.ai.rag.ingestion.engine.fetcher.DocumentSource;
import com.nageoffer.ai.rag.ingestion.engine.enums.SourceType;
import com.nageoffer.ai.rag.ingestion.service.IngestionEngineService;
import com.nageoffer.ai.rag.ingestion.service.IngestionService;
import com.nageoffer.ai.rag.ingestion.service.IngestionResult;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 入库服务（P1 改造：从线性 ETL 改为走节点引擎）。
 *
 * <p>原来的 readerSelector→TokenTextSplitter→vectorStore.add 线性链已退役，
 * 现在把字节 + 来源交给 {@link IngestionEngineService}，由节点引擎编排（P1 走默认 4 节点 stub）。
 * 节点真实化见 P2（Parser/Fetcher）/P3（Chunker/Indexer）/P4（Enhancer/Enricher）。
 */
@Service
@RequiredArgsConstructor
public class DefaultIngestionServiceImpl implements IngestionService {

    private final IngestionEngineService engineService;

    /** P1 用内存默认 pipeline；P2 从 props.getDefaultPipelineId() 读。 */
    private static final String DEFAULT_PIPELINE = "default";

    @Override
    @Transactional
    public IngestionResult ingest(Resource resource, String filename, String mimeType, Long collectionId, Boolean plainText) throws IOException {
        byte[] bytes = resource.getInputStream().readAllBytes();
        DocumentSource source = DocumentSource.builder()
                .type(SourceType.FILE)
                .location(filename)
                .fileName(filename)
                .build();
        return engineService.executeTask(DEFAULT_PIPELINE, source, bytes, mimeType, collectionId, plainText);
    }
}
