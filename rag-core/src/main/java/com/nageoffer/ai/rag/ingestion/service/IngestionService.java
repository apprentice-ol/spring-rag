package com.nageoffer.ai.rag.ingestion.service;

/** 文档入库服务：解析 → 分块 → 向量化 → 写业务表。 */
public interface IngestionService {

    /**
     * 入库一份文档。
     *
     * @param bytes        文档字节（调用方各自读取一次，避免 Resource 多份拷贝）
     * @param filename     原始文件名
     * @param mimeType     MIME 类型
     * @param collectionId 所属集合（可空）
     * @param plainText    纯文本切分开关（可空）
     * @param docId        文档标识；空则内部生成。MQ 异步链<b>必须</b>传消息里的 docId
     *                     （幂等锚点 + 与 /upload-async 返回给前端的 docId 对齐）
     */
    IngestionResult ingest(byte[] bytes, String filename, String mimeType,
                           Long collectionId, Boolean plainText, String docId);
}
