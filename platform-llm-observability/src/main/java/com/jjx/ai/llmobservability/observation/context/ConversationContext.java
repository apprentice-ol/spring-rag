package com.jjx.ai.llmobservability.observation.context;

import com.jjx.ai.llmobservability.observation.span.CapturedSpan;
import com.jjx.ai.llmobservability.observation.span.SpanWriter;
import com.jjx.ai.llmobservability.observation.support.AttributeKeys;
import com.jjx.ai.llmobservability.observation.support.SpanIoLimits;
import com.jjx.ai.llmobservability.observation.ObservationPipeline;
import com.jjx.ai.llmobservability.observation.event.TelemetryEvent;
import com.jjx.ai.llmobservability.observation.propagation.GenAiAttributePropagationSpanProcessor;
import io.opentelemetry.api.trace.Span;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 一次请求/对话的环境级 ambient 上下文：承载 trace 级 input/output（用户问题 / 最终回答）与 trace 级维度。
 *
 * <p><b>所属维度</b>：②event（@internal，业务经门面 {@code conversationOutput} / {@code traceDimension} 使用，不直接依赖）。</p>
 *
 * <p><b>职责</b>：把对话级 IO 写到 HTTP 根 span——产 TRACE_IO 事件走 {@link ObservationPipeline}（原文不摘要，
 * 仅截断）。input 只设一次，output 可多次（以最后一次为准）。维度（intent/agent 等低基数键值）累积在本上下文，
 * 由 {@link #dimension} 统一落键（具体 key 与后端映射业务不感知）。</p>
 *
 * <p><b>协作</b>：由 {@code TelemetryTemplate.beginConversation} 构造（捕获当前 server span）；经
 * {@code ConversationContextAccessor} 跨线程透传；output 由业务经门面写。</p>
 *
 * <p><b>为何持 {@link CapturedSpan}</b>：回答在 Reactor 流式回调线程产生，{@code Span.current()}
 * 不保证恢复为根 span，持构造期捕获引用才稳。</p>
 */
public final class ConversationContext {

    private final Span rootSpan;
    private final SpanWriter rootWriter;
    private final ObservationPipeline pipeline;
    private final Map<String, String> dimensions = new LinkedHashMap<>();
    /** 入口步骤 span（可选）：绑定后 trace 级 output 同时写到根 span 与入口 step span，避免流式入口 span 只有 input。 */
    private volatile SpanWriter stepWriter;
    private volatile Map<String, String> latestGenAiAttributes;
    private volatile boolean inputSet;

    public ConversationContext(Span rootSpan, ObservationPipeline pipeline) {
        this.rootSpan = rootSpan;
        this.rootWriter = new CapturedSpan(rootSpan);
        this.pipeline = pipeline;
    }

    public String traceId() {
        return rootSpan.getSpanContext().getTraceId();
    }

    /** 记 trace 级 input（首个 user 消息 = 用户问题）。重复调用忽略。 */
    public void input(Object value) {
        if (inputSet || value == null) {
            return;
        }
        inputSet = true;
        emitIo(AttributeKeys.TRACE_INPUT, toMessageArray("user", value));
    }

    /** 记 trace 级 output（assistant 回答）。同一请求内多次以最后一次为准。 */
    public void output(Object value) {
        // 对话级 output 只落根 span（消息数组 + 后端映射）；入口 step 只拿裸 output 键。
        // 每个 span 每方向一套键：Langfuse 认裸键与 langfuse.observation.*，OpenObserve 面板认裸键，
        // 双写只会让两列同值重复。
        emitIo(AttributeKeys.TRACE_OUTPUT, toMessageArray("assistant", value));
        flushGenAiAttributes();
        SpanWriter step = stepWriter;
        if (step != null && value != null) {
            step.setAttribute(AttributeKeys.STEP_OUTPUT, truncate(String.valueOf(value)));
        }
    }

    /**
     * 按 OTel GenAI 语义约定把 trace 级 IO 规范成消息数组（JSON），
     * 避免 OpenObserve LLM 表格把裸字符串当非法 JSON 解析后显示为空。
     * 非字符串对象保持原样交给 exporter（例如调用方已传结构化对象）。
     */
    private Object toMessageArray(String role, Object value) {
        if (value instanceof CharSequence cs) {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", role);
            message.put("content", cs.toString());
            return List.of(message);
        }
        return value;
    }

    /** 绑定入口步骤 span（仅首次生效），用于把最终回答同时落到入口 step span。 */
    public void bindStepWriter(SpanWriter writer) {
        if (writer != null && stepWriter == null) {
            stepWriter = writer;
        }
    }

    /**
     * 鎶婃渶鍐呭眰 LLM span 鐨?GenAI 鍏抽敭灞炴€у鍒跺埌宸茬粦瀹氱殑鍏ュ彛 step span銆?     *
     * <p>OpenObserve 鐨?traces 璇︽儏/鍒楄〃閫氬父浼氱湅鍏ュ彛 step锛堜緥濡?{@code rag.chat}锛夛紝
     * 浣?Spring AI 鏍囧噯璇箟鍙妸 {@code gen_ai.request.model}/{@code gen_ai.usage.*} 鍐欏湪鏈€鍐呭眰
     * {@code chat deepseek-chat} span 涓娿€傝繖閲屽湪 LLM span 缁撴潫鏃剁敱
     * {@code GenAiAttributePropagationSpanProcessor} 鍥炶皟锛屾妸鏈€鍚庝竴娆＄湡瀹炴ā鍨嬭皟鐢ㄧ殑
     * model/usage 琛ュ埌鍏ュ彛 step锛岃€屼笉鏀瑰姩鏍囧噯 span 灞傜骇銆?     */
    public void applyGenAiAttributes(Map<String, String> attributes) {
        try {
            if (attributes == null || attributes.isEmpty()) {
                return;
            }
            Map<String, String> copy = new LinkedHashMap<>();
            for (String key : attributes.keySet()) {
                String value = attributes.get(key);
                if (value != null && !value.isBlank()) {
                    copy.put(key, value);
                }
            }
            latestGenAiAttributes = copy;
        } catch (Throwable ignored) {
            // 观测层永不打断业务
        }
    }

    private void flushGenAiAttributes() {
        try {
            Map<String, String> attrs = GenAiAttributePropagationSpanProcessor.latestAttributes(traceId());
            if (attrs == null || attrs.isEmpty()) {
                attrs = latestGenAiAttributes;
            }
            if (attrs == null || attrs.isEmpty()) {
                return;
            }
            attrs.forEach((key, value) -> {
                // 写 span 用改名副本（llm.summary.*）：标准 gen_ai.* 键落到根/入口 span 会被
                // Langfuse 判成 GENERATION 并重复落 usage（token 多倍计）；OpenObserve 按属性名
                // 展示，改名不影响其可见性
                Map.Entry<String, String> renamed = Map.entry(
                        "llm.summary." + key, value);
                rootWriter.setAttribute(renamed.getKey(), renamed.getValue());
                SpanWriter step = stepWriter;
                if (step != null) {
                    step.setAttribute(renamed.getKey(), renamed.getValue());
                }
            });
        } catch (Throwable ignored) {
            // 观测层永不打断业务
        }
    }

    /**
     * 记 trace 级维度（低基数键值，如 intent/agent）。落在根 span：每键一个 metadata 属性，
     * 并把累积的全部维度拼成单一 tags 属性（后端列表过滤/聚合用）。
     */
    public void dimension(String key, String value) {
        try {
            if (key == null || key.isBlank() || value == null || value.isBlank()) {
                return;
            }
            dimensions.put(key, value);
            emitAttribute(AttributeKeys.traceMetadata(key), value);
            emitAttribute(AttributeKeys.traceTags(), dimensions.entrySet().stream()
                    .map(e -> e.getKey() + ":" + e.getValue())
                    .collect(Collectors.joining(",")));
        } catch (Throwable ignored) {
            // 观测层永不打断业务
        }
    }

    private void emitAttribute(String key, String value) {
        TelemetryEvent event = new TelemetryEvent(TelemetryEvent.EventType.ATTRIBUTE, "conversation", null, value);
        event.setIoKey(key);
        pipeline.emit(event, rootWriter);
    }

    private void emitIo(String key, Object value) {
        TelemetryEvent event = new TelemetryEvent(TelemetryEvent.EventType.TRACE_IO, "conversation", null, value);
        event.setIoKey(key);
        pipeline.emit(event, rootWriter);
    }

    private String truncate(String s) {
        try {
            if (!SpanIoLimits.isTruncateEnabled()) {
                return s;
            }
            return s.length() <= SpanIoLimits.maxSpanIo()
                    ? s
                    : s.substring(0, SpanIoLimits.maxSpanIo());
        } catch (Throwable ignored) {
            // 旧版 SpanIoLimits 缺少方法等场景：降级为原样返回，不影响业务
            return s;
        }
    }
}
