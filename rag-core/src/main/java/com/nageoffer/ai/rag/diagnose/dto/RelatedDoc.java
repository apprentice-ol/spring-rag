package com.nageoffer.ai.rag.diagnose.dto;

/** 诊断检索命中的知识库片段。 */
public record RelatedDoc(
        String docName,         // 来源文档名
        String outlinePath,     // 章节路径（如 "第3章 > 3.2 销售分析"）
        String contentPreview,  // 内容预览（截断）
        double score            // 相关度（rerank 分）
) {
}
