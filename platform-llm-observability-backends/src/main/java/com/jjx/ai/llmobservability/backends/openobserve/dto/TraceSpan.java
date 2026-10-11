package com.jjx.ai.llmobservability.backends.openobserve.dto;

import java.util.Map;

/**
 * OpenObserve trace span（链路详情读取）。
 *
 * <p>常见字段（span_id/name/start_time/...）单独映射，其余字段（含拍平的
 * {@code gen_ai_*} 属性）统一放入 {@code attributes}，避免后端新增字段时频繁改 DTO。</p>
 *
 * @param spanId       span id
 * @param traceId      所属 trace id
 * @param parentSpanId 父 span id；根 span 时为空
 * @param name         span 名（如 rag.answer）
 * @param serviceName  服务名
 * @param operationName 操作名（可为空）
 * @param startTimeUs  开始时间（微秒）
 * @param endTimeUs    结束时间（微秒）
 * @param durationUs   耗时（微秒）
 * @param status       span 状态（如 OK/ERROR/UNSET）
 * @param attributes   其余原始字段（含 gen_ai_* 拍平属性）
 */
public record TraceSpan(
        String spanId,
        String traceId,
        String parentSpanId,
        String name,
        String serviceName,
        String operationName,
        long startTimeUs,
        long endTimeUs,
        long durationUs,
        String status,
        Map<String, Object> attributes
) {
}
