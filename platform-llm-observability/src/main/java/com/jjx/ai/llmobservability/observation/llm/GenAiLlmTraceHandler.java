package com.jjx.ai.llmobservability.observation.llm;

import com.jjx.ai.llmobservability.observation.support.AttributeKeys;
import com.jjx.ai.llmobservability.observation.support.SpanIoLimits;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;

/**
 * 默认 {@link LlmTraceHandler}：把一次框架无关的 LLM 调用规范化为 OTel GenAI 语义（gen_ai.*）。
 *
 * <p>键名对齐当前 OTel GenAI 语义约定：usage 使用 {@code gen_ai.usage.input_tokens/output_tokens}，
 * 避免与 Spring AI 原生观察（同样输出这两个键）并存时出现 {@code prompt_tokens/completion_tokens} 的旧键。</p>
 *
 * <p>此实现面向“宿主 LLM SDK 不自带观察”的场景；使用 Spring AI 时不应重复调用，因为 Spring AI 已输出同等语义。</p>
 */
public class GenAiLlmTraceHandler implements LlmTraceHandler {

    private static final String OPERATION_CHAT = "chat";

    @Override
    public void trace(Observation.Context context, LlmCall call) {
        if (call == null) {
            return;
        }
        try {
            addLow(context, AttributeKeys.GEN_AI_OPERATION_NAME, OPERATION_CHAT);
            addLow(context, AttributeKeys.GEN_AI_SYSTEM, call.getSystem());
            addLow(context, AttributeKeys.GEN_AI_REQUEST_MODEL, call.getModel());
            addHigh(context, AttributeKeys.GEN_AI_PROMPT, call.getPrompt());
            addHigh(context, AttributeKeys.GEN_AI_COMPLETION, call.getCompletion());
            addNum(context, AttributeKeys.GEN_AI_USAGE_INPUT_TOKENS, call.getPromptTokens());
            addNum(context, AttributeKeys.GEN_AI_USAGE_OUTPUT_TOKENS, call.getCompletionTokens());
            addNum(context, AttributeKeys.GEN_AI_USAGE_TOTAL_TOKENS, call.getTotalTokens());
        } catch (Throwable ignored) {
            // 绝不影响 observation 生命周期
        }
    }

    private void addLow(Observation.Context ctx, String key, String value) {
        if (value != null) {
            ctx.addLowCardinalityKeyValue(KeyValue.of(key, value));
        }
    }

    private void addHigh(Observation.Context ctx, String key, String value) {
        if (value != null) {
            ctx.addHighCardinalityKeyValue(KeyValue.of(key, truncate(value)));
        }
    }

    private void addNum(Observation.Context ctx, String key, Integer value) {
        if (value != null) {
            ctx.addHighCardinalityKeyValue(KeyValue.of(key, String.valueOf(value)));
        }
    }

    private String truncate(String s) {
        if (!SpanIoLimits.isTruncateEnabled()) {
            return s;
        }
        return s.length() <= SpanIoLimits.maxSpanIo()
                ? s
                : s.substring(0, SpanIoLimits.maxSpanIo()) + "…[truncated]";
    }
}
