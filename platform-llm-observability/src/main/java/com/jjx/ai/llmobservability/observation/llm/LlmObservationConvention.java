package com.jjx.ai.llmobservability.observation.llm;

import com.jjx.ai.llmobservability.observation.support.AttributeKeys;
import com.jjx.ai.llmobservability.observation.support.SpanIoLimits;
import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.micrometer.observation.ObservationConvention;

/**
 * LLM 调用的 OTel GenAI 语义约定，供 {@link LlmObservations} 生成标准 gen_ai.* 属性。
 */
public class LlmObservationConvention implements ObservationConvention<LlmObservationContext> {

    public static final String NAME = AttributeKeys.GEN_AI_CLIENT_OPERATION;

    private static final LlmObservationConvention INSTANCE = new LlmObservationConvention();

    public static LlmObservationConvention instance() {
        return INSTANCE;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getContextualName(LlmObservationContext context) {
        return NAME;
    }

    @Override
    public boolean supportsContext(io.micrometer.observation.Observation.Context context) {
        return context instanceof LlmObservationContext;
    }

    @Override
    public KeyValues getLowCardinalityKeyValues(LlmObservationContext ctx) {
        return KeyValues.of(
                KeyValue.of(AttributeKeys.GEN_AI_OPERATION_NAME, "chat"),
                KeyValue.of(AttributeKeys.GEN_AI_SYSTEM, nullSafe(ctx.getSystem())),
                KeyValue.of(AttributeKeys.GEN_AI_REQUEST_MODEL, nullSafe(ctx.getModel())));
    }

    @Override
    public KeyValues getHighCardinalityKeyValues(LlmObservationContext ctx) {
        KeyValues values = KeyValues.empty();
        if (ctx.getPromptText() != null) {
            values = values.and(KeyValue.of(AttributeKeys.GEN_AI_PROMPT, truncate(ctx.getPromptText())));
        }
        if (ctx.getCompletion() != null) {
            values = values.and(KeyValue.of(AttributeKeys.GEN_AI_COMPLETION, truncate(ctx.getCompletion())));
        }
        LlmUsage usage = ctx.getUsage();
        if (usage != null) {
            if (usage.inputTokens() != null) {
                values = values.and(KeyValue.of(AttributeKeys.GEN_AI_USAGE_INPUT_TOKENS, String.valueOf(usage.inputTokens())));
            }
            if (usage.outputTokens() != null) {
                values = values.and(KeyValue.of(AttributeKeys.GEN_AI_USAGE_OUTPUT_TOKENS, String.valueOf(usage.outputTokens())));
            }
            if (usage.totalTokens() != null) {
                values = values.and(KeyValue.of(AttributeKeys.GEN_AI_USAGE_TOTAL_TOKENS, String.valueOf(usage.totalTokens())));
            }
        }
        return values;
    }

    private String nullSafe(String s) {
        return s == null ? "unknown" : s;
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
