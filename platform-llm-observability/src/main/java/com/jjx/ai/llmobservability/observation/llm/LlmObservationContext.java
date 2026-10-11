package com.jjx.ai.llmobservability.observation.llm;

import io.micrometer.observation.Observation;

/**
 * LLM 调用观察的上下文：承载一次调用请求/响应/用量，供 {@link LlmObservationConvention} 生成 gen_ai.* 属性。
 */
public class LlmObservationContext extends Observation.Context {

    private final String system;
    private final String model;
    private final Object prompt;
    private String completion;
    private LlmUsage usage;

    public LlmObservationContext(String system, String model, Object prompt) {
        this.system = system;
        this.model = model;
        this.prompt = prompt;
    }

    public void recordCompletion(String completion, LlmUsage usage) {
        this.completion = completion;
        this.usage = usage;
    }

    public String getSystem() {
        return system;
    }

    public String getModel() {
        return model;
    }

    public String getPromptText() {
        return prompt == null ? null : String.valueOf(prompt);
    }

    public String getCompletion() {
        return completion;
    }

    public LlmUsage getUsage() {
        return usage;
    }
}
