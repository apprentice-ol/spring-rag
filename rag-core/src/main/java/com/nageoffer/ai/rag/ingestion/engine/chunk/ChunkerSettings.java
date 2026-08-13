package com.nageoffer.ai.rag.ingestion.engine.chunk;
import com.nageoffer.ai.rag.ingestion.engine.chunk.strategy.ChunkingMode;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分块器设置。
 * <p>定义文档分块节点的配置参数。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChunkerSettings {

    /** 分块策略（固定大小 / 按结构切分等） */
    private ChunkingMode strategy;

    /** 块的目标大小（字符数或 token 数） */
    private Integer chunkSize;

    /** 相邻块之间的重叠大小 */
    private Integer overlapSize;

    /** 自定义分割符 */
    private String separator;

    /** 表格每个 chunk 的最大数据行数（table chunker 硬上限） */
    private Integer rowsPerChunk;
}
