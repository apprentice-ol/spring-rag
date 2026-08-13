package com.nageoffer.ai.rag.ingestion.engine.enricher;

import com.nageoffer.ai.rag.ingestion.engine.enums.ChunkEnrichType;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 丰富器设置（VLM 生成图片描述等配置）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EnricherSettings {

    /** 丰富任务列表 */
    private List<ChunkEnrichTask> tasks;

    /** 是否附加文档元数据到每个 chunk */
    private Boolean attachDocumentMetadata;

    /** 单个 chunk 丰富任务。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ChunkEnrichTask {

        /** 丰富类型 */
        private ChunkEnrichType type;

        /** LLM 系统提示词 */
        private String systemPrompt;

        /** 用户提示词模板 */
        private String userPromptTemplate;
    }
}
