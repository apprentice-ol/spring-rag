package com.nageoffer.ai.obs.springai;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import org.slf4j.MDC;
import org.springframework.ai.chat.observation.ChatModelObservationContext;

/**
 * 把 obs 的 RAG 领域上下文（conversation_id / 当前 pipeline step）挂到 Spring AI 原生的 gen_ai span 上。
 *
 * <p>只补“关联字段”，不重写 prompt/completion/usage——这些 Spring AI 原生已按 gen_ai.* 输出。</p>
 */
public class SpringAiConversationCorrelationFilter implements ObservationFilter {

    private static final String MDC_CONVERSATION_ID = "conversation_id";
    private static final String MDC_STEP = "step";

    @Override
    public Observation.Context map(Observation.Context context) {
        if (context instanceof ChatModelObservationContext) {
            String conversationId = MDC.get(MDC_CONVERSATION_ID);
            if (conversationId != null && !conversationId.isBlank()) {
                context.addHighCardinalityKeyValue(KeyValue.of("conversation.id", conversationId));
            }
            String step = MDC.get(MDC_STEP);
            if (step != null && !step.isBlank()) {
                context.addLowCardinalityKeyValue(KeyValue.of("rag.step", step));
            }
        }
        return context;
    }
}
