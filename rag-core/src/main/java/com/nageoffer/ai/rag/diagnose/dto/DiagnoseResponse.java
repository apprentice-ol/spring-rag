package com.nageoffer.ai.rag.diagnose.dto;

import com.nageoffer.ai.llmobservability.backends.openobserve.dto.TraceLogEntry;
import java.util.List;

/**
 * 日志诊断结果。
 *
 * <ul>
 *   <li>logs：该 traceId 的全量日志（按时序）</li>
 *   <li>errors：从 logs 过滤出的报错（level=ERROR 或带 exception）</li>
 *   <li>relatedDocs：用报错检索到的知识库片段</li>
 *   <li>suggestion：LLM 基于「报错 + 文档」生成的根因分析与修复建议（Markdown）</li>
 * </ul>
 */
public record DiagnoseResponse(
        String traceId,
        List<TraceLogEntry> logs,
        List<TraceLogEntry> errors,
        List<RelatedDoc> relatedDocs,
        String suggestion
) {
}
