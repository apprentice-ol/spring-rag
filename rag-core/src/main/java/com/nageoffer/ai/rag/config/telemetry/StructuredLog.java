package com.nageoffer.ai.rag.config.telemetry;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * 结构化日志：把 RAG 步骤事件拼成固定 schema 的 JSON 串打 {@code log.info}，
 * 由 logback OPENOBSERVE appender（{@code OpenObserveAppender}）自动转发到 OpenObserve。
 *
 * <p>schema：{@code {"_event":"step.output","rag_step":"rewrite","step_id":"<spanId>","data":{...},"duration_ms":820}}。
 * <p>{@code rag_step/step_id} 同时由 {@link RagTelemetry#step} 写入 MDC，
 * {@code OpenObserveAppender} 的 {@code putAll(mdc)} 会平铺成 OpenObserve 顶层字段，
 * 便于按步骤 / spanId 过滤聚合；{@code data} 放完整输入输出摘要。</p>
 */
public final class StructuredLog {

    private static final Logger log = LoggerFactory.getLogger("rag.telemetry");
    private static final Gson GSON = new Gson();

    private StructuredLog() {
    }

    /**
     * 发一条结构化日志。
     *
     * @param event      事件名（step.input / step.output / llm.request / llm.response）
     * @param step       步骤名（rag_step），可空
     * @param stepId     spanId（step_id），可空
     * @param data       载荷（已摘要），可空
     * @param durationMs 耗时（仅 step.output），可空
     */
    public static void emit(String event, String step, String stepId, Object data, Long durationMs) {
        JsonObject obj = new JsonObject();
        obj.addProperty("_event", event);
        if (step != null) {
            obj.addProperty("rag_step", step);
        }
        if (stepId != null) {
            obj.addProperty("step_id", stepId);
        }
        if (durationMs != null) {
            obj.addProperty("duration_ms", durationMs);
        }
        if (data != null) {
            obj.add("data", GSON.toJsonTree(data));
        }
        log.info(obj.toString());
    }

    /**
     * 发一条结构化日志，step/stepId 自动从当前 MDC 取（须在 step span scope 内调用，否则该事件不关联 trace span）。
     * 供「在某个 step 内发附属事件」的场景（llm.request / rerank.scores 等），免去手写 {@code MDC.get} 样板。
     */
    public static void emit(String event, Object data) {
        emit(event, MDC.get("rag_step"), MDC.get("step_id"), data, null);
    }
}
