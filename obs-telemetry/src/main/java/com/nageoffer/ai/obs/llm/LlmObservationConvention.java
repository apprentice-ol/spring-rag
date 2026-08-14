package com.nageoffer.ai.obs.llm;

import com.nageoffer.ai.obs.processor.SpanIoLimits;
import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.micrometer.observation.ObservationConvention;

/**
 * LLM 调用的 OTel GenAI 语义约定，供 {@link LlmObservations} 生成标准 gen_ai.* 属性。
 */
public class LlmObservationConvention implements ObservationConvention<LlmObservationContext> {

    public static final String NAME = "gen_ai.client.operation";

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
                KeyValue.of("gen_ai.operation.name", "chat"),
                KeyValue.of("gen_ai.system", nullSafe(ctx.getSystem())),
                KeyValue.of("gen_ai.request.model", nullSafe(ctx.getModel())));
    }

    @Override
    public KeyValues getHighCardinalityKeyValues(LlmObservationContext ctx) {
        KeyValues values = KeyValues.empty();
        if (ctx.getPromptText() != null) {
            values = values.and(KeyValue.of("gen_ai.prompt", truncate(ctx.getPromptText())));
        }
        if (ctx.getCompletion() != null) {
            values = values.and(KeyValue.of("gen_ai.completion", truncate(ctx.getCompletion())));
        }
        LlmUsage usage = ctx.getUsage();
        if (usage != null) {
            if (usage.inputTokens() != null) {
                values = values.and(KeyValue.of("gen_ai.usage.input_tokens", String.valueOf(usage.inputTokens())));
            }
            if (usage.outputTokens() != null) {
                values = values.and(KeyValue.of("gen_ai.usage.output_tokens", String.valueOf(usage.outputTokens())));
            }
            if (usage.totalTokens() != null) {
                values = values.and(KeyValue.of("gen_ai.usage.total_tokens", String.valueOf(usage.totalTokens())));
            }
        }
        return values;
    }

    private String nullSafe(String s) {
        return s == null ? "unknown" : s;
    }

    private String truncate(String s) {
        return s.length() <= SpanIoLimits.MAX_SPAN_IO
                ? s
                : s.substring(0, SpanIoLimits.MAX_SPAN_IO) + "…[truncated]";
    }
}
