package com.nageoffer.ai.rag.ingestion.service;

import com.nageoffer.ai.rag.ingestion.service.IngestionResult;
import org.springframework.core.io.Resource;

import java.io.IOException;

/** 文档入库服务：解析 → 分块 → 向量化 → 写业务表。 */
public interface IngestionService {

    IngestionResult ingest(Resource resource, String filename, String mimeType, Long collectionId, Boolean plainText) throws IOException;
}
