package com.nageoffer.ai.obs.observation.llm;

import com.nageoffer.ai.obs.observation.support.SpanIoLimits;
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
            addLow(context, "gen_ai.operation.name", OPERATION_CHAT);
            addLow(context, "gen_ai.system", call.getSystem());
            addLow(context, "gen_ai.request.model", call.getModel());
            addHigh(context, "gen_ai.prompt", call.getPrompt());
            addHigh(context, "gen_ai.completion", call.getCompletion());
            addNum(context, "gen_ai.usage.input_tokens", call.getPromptTokens());
            addNum(context, "gen_ai.usage.output_tokens", call.getCompletionTokens());
            addNum(context, "gen_ai.usage.total_tokens", call.getTotalTokens());
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
        return s.length() <= SpanIoLimits.MAX_SPAN_IO
                ? s
                : s.substring(0, SpanIoLimits.MAX_SPAN_IO) + "…[truncated]";
    }
}
