package com.nageoffer.ai.rag.ingestion.engine.indexer;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 索引器设置（向量入库的配置）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IndexerSettings {

    /** 向量存储类型（pgvector / milvus 等） */
    private String vectorStore;

    /** 批量入库大小 */
    private Integer batchSize;

    /** 需要写入 metadata 的白名单字段（如 keywords、summary 等） */
    private List<String> metadataFields;
}
