package com.jjx.ai.llmobservability.autoconfigure.springai;

import com.jjx.ai.llmobservability.observation.support.AttributeKeys;
import com.jjx.ai.llmobservability.observation.support.ObservationJson;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.observation.EmbeddingModelObservationContext;

/**
 * embedding observation 的 IO 补写：Spring AI 1.1.x 的 embedding span 只有模型/用量属性，
 * input/output 两侧皆空（2026-10-11 真实 trace 实测），检索链路里"嵌入的是哪段文本"无从回看。
 *
 * <p>与 {@link ChatModelCompletionObservationHandler} 同模式：handler 挂在 observation 生命周期上，
 * onStart 补输入（{@code gen_ai.prompt}），onStop 补输出占位（{@code gen_ai.completion}）——
 * 向量本体无展示价值，输出写成 {@code {"dimensions":n,"count":m}} 的可读摘要即可。</p>
 */
public class EmbeddingObservationHandler implements ObservationHandler<EmbeddingModelObservationContext> {

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof EmbeddingModelObservationContext;
    }

    @Override
    public void onStart(EmbeddingModelObservationContext context) {
        try {
            String promptJson = instructionsToJson(context.getRequest());
            if (promptJson != null) {
                context.addHighCardinalityKeyValue(KeyValue.of(AttributeKeys.GEN_AI_PROMPT, promptJson));
            }
        } catch (Throwable ignored) {
            // 观测补写绝不能影响业务
        }
    }

    @Override
    public void onStop(EmbeddingModelObservationContext context) {
        try {
            String completionJson = responseToJson(context.getResponse());
            if (completionJson != null) {
                context.addHighCardinalityKeyValue(KeyValue.of(AttributeKeys.GEN_AI_COMPLETION, completionJson));
            }
        } catch (Throwable ignored) {
            // 观测补写绝不能影响业务
        }
    }

    /** 输入与 Spring AI 其他补写组件同形态：消息数组 JSON（role=user） */
    private String instructionsToJson(EmbeddingRequest request) {
        if (request == null || request.getInstructions() == null || request.getInstructions().isEmpty()) {
            return null;
        }
        List<Map<String, String>> messages = new ArrayList<>();
        for (String instruction : request.getInstructions()) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("role", "user");
            entry.put("content", instruction);
            messages.add(entry);
        }
        return ObservationJson.toJson(messages);
    }

    /** 输出只报形状：向量本体（1024 维浮点）进 span 无信息增量，反而撑体积 */
    private String responseToJson(EmbeddingResponse response) {
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return null;
        }
        List<Embedding> results = response.getResults();
        float[] first = results.get(0) == null ? null : results.get(0).getOutput();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("count", results.size());
        if (first != null) {
            summary.put("dimensions", first.length);
        }
        return ObservationJson.toJson(summary);
    }
}
