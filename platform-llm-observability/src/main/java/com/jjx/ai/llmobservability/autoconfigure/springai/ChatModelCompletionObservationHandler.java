package com.jjx.ai.llmobservability.autoconfigure.springai;

import com.jjx.ai.llmobservability.observation.support.AttributeKeys;
import com.jjx.ai.llmobservability.observation.support.ObservationJson;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.observation.ChatModelObservationContext;

/**
 * Spring AI 1.1.x 不会把 completion 写成 {@code gen_ai.completion} span 属性，只写到日志。
 * 这里在 observation stop 时（response 已可用）补写 {@code gen_ai.completion}，让 Langfuse / OpenObserve
 * 的 LLM observation 能看到 output。
 */
public class ChatModelCompletionObservationHandler implements ObservationHandler<ChatModelObservationContext> {

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ChatModelObservationContext;
    }

    @Override
    public void onStop(ChatModelObservationContext context) {
        try {
            String completionJson = completionToJson(context.getResponse());
            if (completionJson != null) {
                context.addHighCardinalityKeyValue(KeyValue.of(AttributeKeys.GEN_AI_COMPLETION, completionJson));
            }
        } catch (Throwable ignored) {
            // 观测补写绝不能影响业务
        }
    }

    private String completionToJson(ChatResponse response) {
        if (response == null || response.getResults() == null) {
            return null;
        }
        try {
            List<String> completions = new ArrayList<>();
            response.getResults().forEach(generation -> {
                if (generation.getOutput() != null) {
                    completions.add(generation.getOutput().getText());
                }
            });
            return ObservationJson.toJson(completions);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
