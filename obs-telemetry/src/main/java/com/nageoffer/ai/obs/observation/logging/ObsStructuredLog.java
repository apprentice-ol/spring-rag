package com.nageoffer.ai.obs.observation.logging;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * 结构化日志：把观测步骤事件拼成固定 schema 的 JSON 串打 {@code log.info}，由 logback appender（如 OTel OpenTelemetryAppender）转发到日志后端。
 *
 * <p><b>所属维度</b>：共享（后端中立——发 slf4j，落哪个后端由应用 logback 配置决定）。</p>
 *
 * <p>schema：{@code {"_event":"step.output","step":"rewrite","step_id":"<spanId>","data":{...},"duration_ms":820}}。</p>
 * <p>{@code step/step_id} 同时由 backend 写入 MDC，appender 平铺成顶层字段，便于按步骤/spanId 过滤聚合；{@code data} 放 IO 摘要。</p>
 */
public final class ObsStructuredLog {

    private static final Logger log = LoggerFactory.getLogger("obs.telemetry");
    private static final Gson GSON = new Gson();

    private ObsStructuredLog() {
    }

    /**
     * 发一条结构化日志。
     *
     * @param event      事件名（step.input / step.output / llm.request / llm.response）
     * @param step       步骤名，可空
     * @param stepId     spanId，可空
     * @param data       载荷（已摘要），可空
     * @param durationMs 耗时（仅 step.output），可空
     */
    public static void emit(String event, String step, String stepId, Object data, Long durationMs) {
        JsonObject obj = new JsonObject();
        obj.addProperty("_event", event);
        if (step != null) {
            obj.addProperty("step", step);
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
     * 发一条结构化日志，step/stepId 自动从当前 MDC 取（须在 step span scope 内调用）。
     * 供"在某个 step 内发附属事件"（llm.request / rerank.scores 等），免去手写 {@code MDC.get} 样板。
     */
    public static void emit(String event, Object data) {
        emit(event, MDC.get("step"), MDC.get("step_id"), data, null);
    }
}
