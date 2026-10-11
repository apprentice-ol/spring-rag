package com.jjx.ai.llmobservability.observation.llm;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 面向“LLM SDK 不自带 Micrometer Observation”的统一记录工具。
 *
 * <p>宿主 SDK 集成方只需在真实调用点包一层，即可产出标准 {@code gen_ai.client.operation} span 与 gen_ai.* 属性；
 * 不需要理解 OTel / Observation 细节。</p>
 */
public final class LlmObservations {

    private LlmObservations() {
    }

    public static <T> T record(ObservationRegistry registry, String system, String model,
                               Object prompt, Supplier<T> call) {
        return record(registry, system, model, prompt, call, null, null);
    }

    public static <T> T record(ObservationRegistry registry, String system, String model,
                               Object prompt, Supplier<T> call, Function<T, String> completionExtractor) {
        return record(registry, system, model, prompt, call, completionExtractor, null);
    }

    public static <T> T record(ObservationRegistry registry, String system, String model,
                               Object prompt, Supplier<T> call,
                               Function<T, String> completionExtractor,
                               Function<T, LlmUsage> usageExtractor) {
        LlmObservationContext ctx = new LlmObservationContext(system, model, prompt);
        Observation observation = Observation.createNotStarted(
                        LlmObservationConvention.instance(), () -> ctx, registry)
                .start();
        try {
            T result;
            try (Observation.Scope scope = observation.openScope()) {
                result = call.get();
            }
            String completion = completionExtractor == null ? null : completionExtractor.apply(result);
            LlmUsage usage = usageExtractor == null ? null : usageExtractor.apply(result);
            ctx.recordCompletion(completion, usage);
            return result;
        } catch (Throwable t) {
            observation.error(t);
            throw t;
        } finally {
            observation.stop();
        }
    }
}
