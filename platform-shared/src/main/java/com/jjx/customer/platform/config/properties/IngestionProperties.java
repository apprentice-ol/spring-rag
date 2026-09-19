package com.jjx.customer.platform.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 入库相关配置，绑定 application.yaml 的 rag.ingestion.* */
@Data
@Configuration
@ConfigurationProperties(prefix = "rag.ingestion")
public class IngestionProperties {

    /** 单块目标字符数（结构感知分块的相邻块合并上限；代码/表格等原子块可突破上限） */
    private int chunkSize = 1000;

    /** 相邻块重叠字符数 */
    private int chunkOverlap = 200;

    /**
     * 子块切分（小块检索、大块生成，2026-09-18；设计见 plan/2026-09-18-small-to-big-retrieval.md）。
     *
     * <p>chunker 产物 = 父块（生成侧上下文单元，不变）；IndexerNode 把父块再切成子块进向量表，
     * 父块原文存 sa_chunk_parent。检索侧由 ParentAggregationPostProcessor 按子块打分、
     * 出父块内容。</p>
     */
    private ChildChunk childChunk = new ChildChunk();

    /** 子块切分参数。 */
    @Data
    public static class ChildChunk {

        /** 是否切子块（关闭后回到"父块直接进向量表"的旧行为；存量数据不受影响） */
        private boolean enabled = true;

        /** 子块目标字符数（BoundaryAwareSplitter 的 targetChars） */
        private int targetChars = 400;

        /** 子块硬上限（maxChars） */
        private int maxChars = 500;

        /** 子块下限（minChars，过小尾块并入前片） */
        private int minChars = 120;

        /** 相邻子块重叠字符数（overlapChars，跨子块边界的答案在相邻块都完整出现） */
        private int overlapChars = 60;
    }
}
