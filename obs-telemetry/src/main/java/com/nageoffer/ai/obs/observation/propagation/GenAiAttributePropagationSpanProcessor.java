package com.nageoffer.ai.obs.observation.propagation;

import com.google.gson.Gson;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import com.nageoffer.ai.obs.observation.context.ObsConversation;
import com.nageoffer.ai.obs.observation.propagation.ObsConversationAccessor;
import com.nageoffer.ai.obs.observation.support.OtelKeys;

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

    private static final Gson GSON = new Gson();

    private static final String[] COPIED_KEYS = {
            OtelKeys.GEN_AI_SYSTEM,
            OtelKeys.GEN_AI_OPERATION_NAME,
            OtelKeys.GEN_AI_REQUEST_MODEL,
            OtelKeys.GEN_AI_RESPONSE_MODEL,
            OtelKeys.GEN_AI_REQUEST_MAX_TOKENS,
            OtelKeys.GEN_AI_REQUEST_TEMPERATURE,
            OtelKeys.GEN_AI_RESPONSE_FINISH_REASONS,
            OtelKeys.GEN_AI_USAGE_INPUT_TOKENS,
            OtelKeys.GEN_AI_USAGE_OUTPUT_TOKENS,
            OtelKeys.GEN_AI_USAGE_TOTAL_TOKENS
    };

    private static final String ENTRY_STEP_NAME = "rag.chat";
    private static final Set<String> FINAL_OUTPUT_SPAN_NAMES = Set.of("rag.answer", "rag.chitchat");
    /** 这些是 GenAI 的“身份字段”，复制到根 span 会把 HTTP 根 span 误判成 LLM span，导致 traces 表格取错输入/输出列。 */
    private static final Set<String> ROOT_EXCLUDED_KEYS = Set.of(
            OtelKeys.GEN_AI_OPERATION_NAME,
            OtelKeys.GEN_AI_SYSTEM);

    private final Map<String, TraceTargets> traces = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, String>> LATEST_ATTRIBUTES = new ConcurrentHashMap<>();

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        String traceId = span.getSpanContext().getTraceId();
        TraceTargets targets = traces.computeIfAbsent(traceId, id -> new TraceTargets());
        if (!span.getParentSpanContext().isValid()) {
            targets.root = span;
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
            applyCopiedAttributes(targets.root, withoutRootExcludedKeys(copied));
            applyCopiedAttributes(targets.entry, copied);
            ObsConversation conversation = ObsConversationAccessor.HOLDER.get();
            if (conversation != null) {
                conversation.applyGenAiAttributes(copied);
            }
        }

        Map<String, String> conversationOutput = conversationOutputAttributes(span.getName(), attrs);
        if (!conversationOutput.isEmpty()) {
            applyCopiedAttributes(targets.root, conversationOutput);
            applyCopiedAttributes(targets.entry, conversationOutput);
        }

        if (targets.root != null
                && span.getSpanContext().getSpanId().equals(targets.root.getSpanContext().getSpanId())) {
            traces.remove(traceId);
            LATEST_ATTRIBUTES.remove(traceId);
        }
    }

    public static Map<String, String> latestAttributes(String traceId) {
        return LATEST_ATTRIBUTES.get(traceId);
    }

    @Override
    public boolean isEndRequired() {
        return true;
    }

    private boolean isRealLlmCall(Map<String, String> attrs) {
        return hasText(attrs.get(OtelKeys.GEN_AI_REQUEST_MODEL))
                || ("chat".equals(attrs.get(OtelKeys.GEN_AI_OPERATION_NAME))
                && (hasText(attrs.get(OtelKeys.GEN_AI_USAGE_TOTAL_TOKENS))
                || hasText(attrs.get(OtelKeys.GEN_AI_USAGE_INPUT_TOKENS))
                || hasText(attrs.get(OtelKeys.GEN_AI_USAGE_OUTPUT_TOKENS))));
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

    private Map<String, String> withoutRootExcludedKeys(Map<String, String> attrs) {
        if (attrs == null || attrs.isEmpty()) {
            return attrs;
        }
        Map<String, String> filtered = new LinkedHashMap<>();
        attrs.forEach((key, value) -> {
            if (!ROOT_EXCLUDED_KEYS.contains(key)) {
                filtered.put(key, value);
            }
        });
        return filtered;
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
        String rawOutput = attrs.get(OtelKeys.STEP_OUTPUT);
        String rawGenAiOutput = attrs.get(OtelKeys.TRACE_OUTPUT);
        if (hasText(rawOutput)) {
            copied.put(OtelKeys.STEP_OUTPUT, rawOutput);
        }
        String outputText = hasText(rawGenAiOutput) ? rawGenAiOutput : rawOutput;
        if (hasText(outputText)) {
            copied.put(OtelKeys.TRACE_OUTPUT, normalizeOutputMessages(outputText));
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
                Object parsed = GSON.fromJson(trimmed, Object.class);
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
        return GSON.toJson(messages);
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
