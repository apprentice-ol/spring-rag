package com.jjx.ai.llmobservability.observation.propagation;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import com.jjx.ai.llmobservability.observation.context.ConversationContext;
import com.jjx.ai.llmobservability.observation.propagation.ConversationContextAccessor;
import com.jjx.ai.llmobservability.observation.support.AttributeKeys;
import com.jjx.ai.llmobservability.observation.support.ObservationJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把真正 LLM generation span 的 GenAI 关键属性复制到 trace 根 span 与入口步骤 span。
 *
 * <p>OpenObserve 的 traces 列表/详情主要看根 span 和入口步骤 span；但 Spring AI 官方语义把
 * {@code gen_ai.request.model}/{@code gen_ai.usage.*} 只写在最内层 {@code chat deepseek-chat} span。
 * 为了在不破坏标准 span 层级的前提下让根/入口也有值，本 processor 在 LLM span 结束时，
 * 把本次 trace 中<b>最近一次真实模型调用</b>的字段实时补到根 span 和 {@code rag.chat} 入口 span。</p>
 *
 * <p>只复制请求/响应模型、system、operation.name、token usage 等低敏感 GenAI 属性；
 * 不复制 prompt/completion 原文，避免把大字段重复写到根 span。业务 step 的
 * {@code gen_ai.input/output.messages} 不带模型和 usage，因此不会被误判为真实 LLM span。</p>
 */
public final class GenAiAttributePropagationSpanProcessor implements SpanProcessor {

    /** OTel SDK 对空名 span 的兜底占位——此刻根 span 名尚未被 HTTP 语义定型，不能拿它当 trace 名 */
    private static final String UNSPECIFIED_SPAN_NAME = "<unspecified span name>";

    private static final String[] COPIED_KEYS = {
            AttributeKeys.GEN_AI_SYSTEM,
            AttributeKeys.GEN_AI_OPERATION_NAME,
            AttributeKeys.GEN_AI_REQUEST_MODEL,
            AttributeKeys.GEN_AI_RESPONSE_MODEL,
            AttributeKeys.GEN_AI_REQUEST_MAX_TOKENS,
            AttributeKeys.GEN_AI_REQUEST_TEMPERATURE,
            AttributeKeys.GEN_AI_RESPONSE_FINISH_REASONS,
            AttributeKeys.GEN_AI_USAGE_INPUT_TOKENS,
            AttributeKeys.GEN_AI_USAGE_OUTPUT_TOKENS,
            AttributeKeys.GEN_AI_USAGE_TOTAL_TOKENS
    };

    private static final String ENTRY_STEP_NAME = "rag.chat";
    private static final Set<String> FINAL_OUTPUT_SPAN_NAMES = Set.of("rag.answer", "rag.chitchat");

    /**
     * 传播键前缀改写：{@code gen_ai.usage.*} 等标准键一旦落到根/入口 span，Langfuse 的 OTLP
     * 摄取会把它们判成 LLM GENERATION 并把 usage 落列——同一次请求的 token 在根、入口、
     * 真实 chat span 各记一遍，成本聚合三倍计（2026-10-11 实测 11214 tokens × 3）。
     * 改写成非标准前缀 {@code llm.summary.*} 后：OpenObserve 侧属性照样可见（它按属性名展示
     * 不挑键），Langfuse 不认非标准键——类型与 usage 回归只在真实 chat span 上。
     */
    private static final String PROPAGATED_PREFIX = "llm.summary.";

    private final Map<String, TraceTargets> traces = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, String>> LATEST_ATTRIBUTES = new ConcurrentHashMap<>();

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        String traceId = span.getSpanContext().getTraceId();
        TraceTargets targets = traces.computeIfAbsent(traceId, id -> new TraceTargets());
        if (!span.getParentSpanContext().isValid()) {
            targets.root = span;
            // 根 span 名在此刻可能尚未被框架定型（Micrometer 的 server span 会被
            // updateName 成 "http post /chat/stream"，onStart 时是 SDK 占位名）。
            // 把占位名写进 langfuse.trace.name 会把 trace 名钉死成 "<unspecified span name>"
            // （2026-10-11 实测）；留空不写时 Langfuse 回退取根 span 的最终名，与
            // OpenObserve 的列表名同源。probe 实测：名字有效时写入仍优先生效。
            String rootName = span.getName();
            if (rootName != null && !rootName.isBlank() && !UNSPECIFIED_SPAN_NAME.equals(rootName)) {
                span.setAttribute("langfuse.trace.name", rootName);
            }
        }
        if (ENTRY_STEP_NAME.equals(span.getName())) {
            targets.entry = span;
        }
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        String traceId = span.getSpanContext().getTraceId();
        TraceTargets targets = traces.get(traceId);
        if (targets == null) {
            return;
        }

        Map<String, String> attrs = stringAttributes(span.getAttributes());
        if (isRealLlmCall(attrs)) {
            Map<String, String> copied = copiedAttributes(attrs);
            if (!copied.isEmpty()) {
                LATEST_ATTRIBUTES.put(traceId, copied);
            }
            // 写 span 一律走改名副本（llm.summary.*）：标准 gen_ai.* 键落到根/入口会触发
            // Langfuse 判型 GENERATION + usage 重复落列；conversation 上下文存原始键
            Map<String, String> renamed = renameForPropagation(copied);
            applyCopiedAttributes(targets.root, renamed);
            applyCopiedAttributes(targets.entry, renamed);
            ConversationContext conversation = ConversationContextAccessor.HOLDER.get();
            if (conversation != null) {
                conversation.applyGenAiAttributes(copied);
            }
        }

        Map<String, String> conversationOutput = conversationOutputAttributes(span.getName(), attrs);
        if (!conversationOutput.isEmpty()) {
            // 分工与 ConversationContext 一致：根 span 收对话级（gen_ai 消息数组），
            // 入口 step 收步骤级（裸 output 键）——兜底不引入第二套键
            applyCopiedAttributes(targets.root, single(AttributeKeys.TRACE_OUTPUT, conversationOutput));
            applyCopiedAttributes(targets.entry, single(AttributeKeys.STEP_OUTPUT, conversationOutput));
        }

        if (targets.root != null
                && span.getSpanContext().getSpanId().equals(targets.root.getSpanContext().getSpanId())) {
            traces.remove(traceId);
            LATEST_ATTRIBUTES.remove(traceId);
        }
    }

    /**
     * 传播副本的键名改写：{@code gen_ai.X} → {@code llm.summary.X}（非 GenAI 标准键）。
     * 供本 processor 与 {@link ConversationContext#flushGenAiAttributes} 共用，两处写法必须一致，
     * 否则同一 span 上会出现标准与非标准两族键，Langfuse 侧前功尽弃。
     */
    public static Map<String, String> renameForPropagation(Map<String, String> attrs) {
        if (attrs == null || attrs.isEmpty()) {
            return Map.of();
        }
        Map<String, String> renamed = new LinkedHashMap<>();
        attrs.forEach((key, value) -> renamed.put(PROPAGATED_PREFIX + key, value));
        return renamed;
    }

    public static Map<String, String> latestAttributes(String traceId) {
        return LATEST_ATTRIBUTES.get(traceId);
    }

    @Override
    public boolean isEndRequired() {
        return true;
    }

    private boolean isRealLlmCall(Map<String, String> attrs) {
        return hasText(attrs.get(AttributeKeys.GEN_AI_REQUEST_MODEL))
                || ("chat".equals(attrs.get(AttributeKeys.GEN_AI_OPERATION_NAME))
                && (hasText(attrs.get(AttributeKeys.GEN_AI_USAGE_TOTAL_TOKENS))
                || hasText(attrs.get(AttributeKeys.GEN_AI_USAGE_INPUT_TOKENS))
                || hasText(attrs.get(AttributeKeys.GEN_AI_USAGE_OUTPUT_TOKENS))));
    }

    private void applyCopiedAttributes(Span target, Map<String, String> attrs) {
        if (target == null) {
            return;
        }
        for (String key : attrs.keySet()) {
            String value = attrs.get(key);
            if (hasText(value)) {
                try {
                    target.setAttribute(key, value);
                } catch (Exception ignored) {
                    // 根/入口 span 已结束或不可写时不影响观测事件流
                }
            }
        }
    }

    /** 从来源 map 取单键，缺值/空值时返回空 map（Map.of 不接受 null 值，必须在这里滤掉）。 */
    private Map<String, String> single(String key, Map<String, String> source) {
        String value = source.get(key);
        return hasText(value) ? Map.of(key, value) : Map.of();
    }

    private Map<String, String> copiedAttributes(Map<String, String> attrs) {
        Map<String, String> copied = new LinkedHashMap<>();
        for (String key : COPIED_KEYS) {
            String value = attrs.get(key);
            if (hasText(value)) {
                copied.put(key, value);
            }
        }
        return copied;
    }

    /**
     * 最终回答 span（rag.answer / rag.chitchat）结束时，把 output 与 gen_ai.output.messages
     * 补写到 root 和 rag.chat 入口 span。这样即使异步流式回写 conversationOutput 时 HTTP server
     * span 已结束，也能尽量让 OpenObserve 外层 traces 表看到最终输出。
     */
    private Map<String, String> conversationOutputAttributes(String spanName, Map<String, String> attrs) {
        if (spanName == null || !FINAL_OUTPUT_SPAN_NAMES.contains(spanName)) {
            return Map.of();
        }
        Map<String, String> copied = new LinkedHashMap<>();
        String rawOutput = attrs.get(AttributeKeys.STEP_OUTPUT);
        String rawGenAiOutput = attrs.get(AttributeKeys.TRACE_OUTPUT);
        if (hasText(rawOutput)) {
            copied.put(AttributeKeys.STEP_OUTPUT, rawOutput);
        }
        String outputText = hasText(rawGenAiOutput) ? rawGenAiOutput : rawOutput;
        if (hasText(outputText)) {
            copied.put(AttributeKeys.TRACE_OUTPUT, normalizeOutputMessages(outputText));
        }
        return copied;
    }

    /**
     * gen_ai.output.messages 必须保持 OTel GenAI 语义：JSON 消息数组。
     * rag.answer / rag.chitchat 的 output 属性可能是裸字符串，这里统一转成
     * [{"role":"assistant","content":"..."}]，避免覆盖根 span 上已有的 JSON。
     */
    private String normalizeOutputMessages(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            try {
                Object parsed = ObservationJson.gson().fromJson(trimmed, Object.class);
                if (parsed instanceof List<?>) {
                    return value;
                }
                if (parsed instanceof Map<?, ?>) {
                    return "[" + value + "]";
                }
            } catch (Exception ignored) {
                // 不是合法 JSON 数组，按裸字符串重新包装
            }
        }
        List<Map<String, String>> messages = new ArrayList<>();
        Map<String, String> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", value);
        messages.add(message);
        return ObservationJson.toJson(messages);
    }

    private Map<String, String> stringAttributes(Attributes attributes) {
        Map<String, String> out = new LinkedHashMap<>();
        attributes.forEach((key, value) -> {
            if (value != null) {
                out.put(key.getKey(), String.valueOf(value));
            }
        });
        return out;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static final class TraceTargets {
        private Span root;
        private Span entry;
    }
}
