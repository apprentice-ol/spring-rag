package com.nageoffer.ai.rag.ingestion.engine;

import com.nageoffer.ai.rag.ingestion.engine.chunk.VectorChunk;
import com.nageoffer.ai.rag.ingestion.engine.enums.IngestionStatus;
import java.util.List;
import java.util.Map;

import com.nageoffer.ai.rag.ingestion.engine.fetcher.DocumentSource;
import com.nageoffer.ai.rag.ingestion.engine.parser.StructuredDocument;
import lombok.Builder;
import lombok.Data;

/**
 * 节点间传递的上下文（输入输出链）。
 *
 * <p>搬自原 ragent ingestion/domain/context/IngestionContext.java（去掉 VectorSpaceId 字段，
 * collection 概念退化为 metadata）。字段流转：
 * rawBytes/mimeType(Fetcher) → rawText/document(Parser) → enhancedText/keywords/questions(Enhancer)
 * → chunks(Chunker) → chunk.metadata(Enricher) → 向量库(Indexer)。
 */
@Data
@Builder
public class IngestionContext {

    private String taskId;
    private String pipelineId;
    private DocumentSource source;

    private byte[] rawBytes;
    private String mimeType;
    private String rawText;
    private StructuredDocument document;

    private List<VectorChunk> chunks;
    private String enhancedText;
    private List<String> keywords;
    private List<String> questions;
    private Map<String, Object> metadata;

    /** 所属集合 ID（来自上传入口）；IndexerNode 写入 chunk metadata.collection_id，供检索侧按集合过滤。
     *  null=独立文件（不属于任何集合）。 */
    private Long collectionId;

    /** 运行时分块路线 override：true=纯文本（丢弃 blocks，走 legacy 边界切分，不保 block 元数据）；
     *  null/false=语义感知（block-aware，保留结构元数据）。由上传入口注入，优先于 pipeline 配置。 */
    private Boolean plainTextChunking;

    private IngestionStatus status;
    private List<NodeLog> logs;
    private Throwable error;
    private boolean skipIndexerWrite;

    /** 文档级资产引用（P5 改为 List<AssetRef>）。 */
    private List<Object> assets;
}
