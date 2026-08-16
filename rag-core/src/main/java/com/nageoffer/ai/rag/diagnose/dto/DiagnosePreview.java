package com.nageoffer.ai.rag.diagnose.dto;

import com.jjx.ai.llmobservability.backends.openobserve.dto.TraceLogEntry;
import java.util.List;

/**
 * 诊断预览：轻量查日志 + 提报错的结果（无 LLM、无检索，~1s）。
 *
 * <p>供对话分支做<b>匹配校验</b>：先 preview 拿到报错摘要，与用户业务意图比对，
 * 匹配后才走 {@code complete}（检索+建议，重）——避免对无关 traceId 浪费完整诊断。
 *
 * <p>语义标记：
 * <ul>
 *   <li>{@code logs} 为空 → 该 traceId 无日志</li>
 *   <li>{@code errors} 为空 → 有日志但无报错</li>
 *   <li>{@code errorSummary} 为 null → 无报错（errors 为空时）</li>
 * </ul>
 */
public record DiagnosePreview(
        String traceId,
        List<TraceLogEntry> logs,
        List<TraceLogEntry> errors,
        String errorSummary
) {
}
