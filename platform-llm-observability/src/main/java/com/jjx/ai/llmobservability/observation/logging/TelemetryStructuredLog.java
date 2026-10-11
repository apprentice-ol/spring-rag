package com.jjx.ai.llmobservability.observation.logging;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.jjx.ai.llmobservability.observation.event.TelemetryEvent;
import com.jjx.ai.llmobservability.observation.filter.TelemetryFilterSupport;
import com.jjx.ai.llmobservability.observation.support.AttributeKeys;
import com.jjx.ai.llmobservability.observation.support.ObservationJson;
import org.slf4j.MDC;

/**
 * 结构化日志：把观测步骤事件拼成固定 schema 的 JSON 串打 {@code log.info}，由 logback appender（如 OTel OpenTelemetryAppender）转发到日志后端。
 *
 * <p><b>所属维度</b>：共享（后端中立——发 slf4j，落哪个后端由应用 logback 配置决定）。</p>
 *
 * <p>schema：{@code {"_event":"step.output","step":"rewrite","step_id":"<spanId>","data":{...},"duration_ms":820}}。</p>
 * <p>{@code step/step_id} 同时由 backend 写入 MDC，appender 平铺成顶层字段，便于按步骤/spanId 过滤聚合；{@code data} 放 IO 摘要。</p>
 */
public final class TelemetryStructuredLog {

    /** 结构化日志固定 logger，logback 过滤器据此跳过已过滤的日志，避免重复执行。 */
    public static final String LOGGER_NAME = "telemetry.telemetry";

    private static final Logger log = LoggerFactory.getLogger(LOGGER_NAME);

    private TelemetryStructuredLog() {
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
        TelemetryEvent telemetryEvent = new TelemetryEvent(TelemetryEvent.EventType.CUSTOM, event, stepId, data);
        if (!TelemetryFilterSupport.pass(telemetryEvent)) {
            return;  // 被过滤器链丢弃
        }
        emitUnfiltered(event, step, stepId, telemetryEvent.getData(), durationMs);
    }

    /**
     * 内部使用：数据已在 {@code ObservationPipeline} 的过滤器链处理过，不再重复过滤。
     * 普通调用方请使用 {@link #emit(String, String, String, Object, Long)}。
     */
    public static void emitUnfiltered(String event, String step, String stepId, Object data, Long durationMs) {
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
            try {
                obj.add("data", ObservationJson.toJsonTree(data));
            } catch (Exception e) {
                obj.addProperty("data", String.valueOf(data));
                obj.addProperty("data_serialization_error", e.getMessage());
            }
        }
        log.info(obj.toString());
    }

    /**
     * 发一条结构化日志，step/stepId 自动从当前 MDC 取（须在 step span scope 内调用）。
     * 供"在某个 step 内发附属事件"（llm.request / rerank.scores 等），免去手写 {@code MDC.get} 样板。
     */
    public static void emit(String event, Object data) {
        emit(event, MDC.get(AttributeKeys.step()), MDC.get(AttributeKeys.stepId()), data, null);
    }

    /** 内部使用：2 参快捷重载的免过滤版本，供已过过滤器链的 exporter 使用。 */
    public static void emitUnfiltered(String event, Object data) {
        emitUnfiltered(event, MDC.get(AttributeKeys.step()), MDC.get(AttributeKeys.stepId()), data, null);
    }
}
