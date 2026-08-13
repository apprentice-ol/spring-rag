package com.nageoffer.ai.rag.config.properties;

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
}
