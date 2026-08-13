package com.nageoffer.ai.rag.ingestion.engine.enhancer;

import java.util.List;

import com.nageoffer.ai.rag.ingestion.engine.enums.EnhanceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 增强器设置（调 LLM 改写/摘要 chunk 的配置）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EnhancerSettings {

    /** 增强任务列表 */
    private List<EnhanceTask> tasks;

    /** 单个增强任务。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class EnhanceTask {

        /** 增强类型 */
        private EnhanceType type;

        /** LLM 系统提示词 */
        private String systemPrompt;

        /** 用户提示词模板 */
        private String userPromptTemplate;
    }
}
