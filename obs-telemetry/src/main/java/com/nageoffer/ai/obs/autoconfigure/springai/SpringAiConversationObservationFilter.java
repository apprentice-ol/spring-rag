package com.nageoffer.ai.obs.autoconfigure.springai;

import com.google.gson.Gson;
import com.nageoffer.ai.obs.observation.support.OtelKeys;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 把 obs 的领域上下文（conversation_id / 当前 pipeline step）以及 prompt 内容挂到 Spring AI 原生的 gen_ai span 上。
 *
 * <p>Spring AI 1.1.x 默认只把 prompt/completion 打到日志（ObservationHandler），不写 {@code gen_ai.prompt} span 属性，
 * 导致 Langfuse/OpenObserve 的 LLM observation 看不到 input/output。这里在 observation 创建时把 prompt 落为
 * {@code gen_ai.prompt}；completion 由 {@link ChatModelCompletionObservationHandler} 在 stop 时补写。</p>
 */
public class SpringAiConversationObservationFilter implements ObservationFilter {

    private static final String MDC_SESSION_ID = OtelKeys.SESSION_ID;
    private static final Gson GSON = new Gson();

    @Override
    public Observation.Context map(Observation.Context context) {
        if (context instanceof ChatModelObservationContext chatContext) {
            String conversationId = MDC.get(MDC_SESSION_ID);
            if (conversationId != null && !conversationId.isBlank()) {
                context.addHighCardinalityKeyValue(KeyValue.of(OtelKeys.GEN_AI_CONVERSATION_ID, conversationId));
            }
            String step = MDC.get(OtelKeys.step());
            if (step != null && !step.isBlank()) {
                context.addLowCardinalityKeyValue(KeyValue.of(OtelKeys.step(), step));
            }
            String promptJson = promptToJson(chatContext.getRequest());
            if (promptJson != null) {
                context.addHighCardinalityKeyValue(KeyValue.of(OtelKeys.GEN_AI_PROMPT, promptJson));
            }
        }
        return context;
    }

    private String promptToJson(Prompt prompt) {
        if (prompt == null || prompt.getInstructions() == null) {
            return null;
        }
        try {
            List<Map<String, String>> messages = new ArrayList<>();
            for (Message message : prompt.getInstructions()) {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("role", message.getMessageType() == null ? "unknown" : message.getMessageType().name());
                entry.put("content", message.getText());
                messages.add(entry);
            }
            return GSON.toJson(messages);
        } catch (Throwable ignored) {
            return prompt.getContents();
        }
    }
}
