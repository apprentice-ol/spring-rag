package com.jjx.ai.llmobservability.autoconfigure.springai;

import com.jjx.ai.llmobservability.observation.llm.LlmPricing;
import com.jjx.ai.llmobservability.observation.support.AttributeKeys;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.ai.embedding.observation.EmbeddingModelObservationContext;

/**
 * LLM/embedding 成本估算（按 {@link LlmPricing}：telemetry.pricing 配置 × 内置参考价）。
 *
 * <p><b>为什么是 ObservationFilter 而不是 ObservationHandler（2026-10-12 实测教训）</b>：
 * handler 的 onStop 执行在 micrometer-tracing 的 OTel 导出 handler 之后，此时 span 属性已定稿——
 * handler 里后加的 keyValue 不会进 span（现网证据：{@link ChatModelCompletionObservationHandler}
 * 补写的 {@code gen_ai.completion} 在 OpenObserve 里从未出现）。filter 的 map 在 stop 阶段先于
 * 所有 handler 执行，且被 {@code SpringAiConversationObservationFilter}（gen_ai.conversation_id）
 * 现网证明可落 span。故成本写入放这里。</p>
 *
 * <p>filter 在 start/stop 两个阶段都会 map：start 阶段 response 尚未产生（null 短路），
 * 只有 stop 阶段会真正计算写入。任何异常静默吞掉——观测补写绝不能影响业务。</p>
 */
public class LlmCostObservationFilter implements ObservationFilter {

    private static final Logger log = LoggerFactory.getLogger(LlmCostObservationFilter.class);

    /** 诊断采样：首次被调用即证明 filter 已挂到 ObservationRegistry（2026-10-12 排查用，可降级 debug）。 */
    private static final AtomicBoolean FIRST_CALL = new AtomicBoolean(true);

    private final LlmPricing pricing;

    public LlmCostObservationFilter(LlmPricing pricing) {
        this.pricing = pricing;
    }

    @Override
    public Observation.Context map(Observation.Context context) {
        try {
            if (FIRST_CALL.compareAndSet(true, false)) {
                log.debug("[LlmCost] filter 已注册，首次 map 调用 context={}", context.getClass().getSimpleName());
            }
            if (context instanceof ChatModelObservationContext chat) {
                addChatCost(chat);
            } else if (context instanceof EmbeddingModelObservationContext embedding) {
                addEmbeddingCost(embedding);
            }
        } catch (Throwable ignored) {
            // 观测补写绝不能影响业务
        }
        return context;
    }

    private void addChatCost(ChatModelObservationContext context) {
        ChatResponse response = context.getResponse();
        if (response == null || response.getMetadata() == null) {
            log.debug("[LlmCost] chat map 时 response/metadata 为空", response);
            return;
        }
        Usage usage = response.getMetadata().getUsage();
        if (usage == null) {
            log.debug("[LlmCost] chat usage 为空（model={}）", response.getMetadata().getModel());
            return;
        }
        String responseModel = response.getMetadata().getModel();
        String requestModel = chatRequestModel(context);
        // 双保险：先按响应侧实际模型名（如 API 中转返回 deepseek-flash），未命中再按请求侧配置名（如 deepseek-chat）
        LlmPricing.UsageCost cost = pricing.compute(responseModel, usage.getPromptTokens(), usage.getCompletionTokens());
        String matchedBy = responseModel;
        if (cost == null && requestModel != null && !requestModel.equalsIgnoreCase(responseModel)) {
            cost = pricing.compute(requestModel, usage.getPromptTokens(), usage.getCompletionTokens());
            matchedBy = requestModel;
        }
        if (cost == null) {
            log.debug("[LlmCost] compute 返回 null：responseModel={} requestModel={} in={} out={}",
                    responseModel, requestModel, usage.getPromptTokens(), usage.getCompletionTokens());
            return;
        }
        log.debug("[LlmCost] chat cost 写入：matched={} total={}", matchedBy, cost.totalText());
        context.addHighCardinalityKeyValue(KeyValue.of(AttributeKeys.GEN_AI_USAGE_COST, cost.totalText()));
        context.addHighCardinalityKeyValue(KeyValue.of(AttributeKeys.GEN_AI_USAGE_COST_INPUT, cost.inputText()));
        context.addHighCardinalityKeyValue(KeyValue.of(AttributeKeys.GEN_AI_USAGE_COST_OUTPUT, cost.outputText()));
    }

    private void addEmbeddingCost(EmbeddingModelObservationContext context) {
        EmbeddingResponse response = context.getResponse();
        if (response == null || response.getMetadata() == null) {
            return;
        }
        EmbeddingResponseMetadata metadata = response.getMetadata();
        Usage usage = metadata.getUsage();
        Integer tokens = usage == null ? null : usage.getTotalTokens();
        if (tokens == null && usage != null) {
            tokens = usage.getPromptTokens();
        }
        String model = metadata.getModel();
        if (model == null || model.isBlank()) {
            model = embeddingRequestModel(context);
        }
        LlmPricing.UsageCost cost = pricing.compute(model, tokens, 0);
        if (cost == null) {
            log.debug("[LlmCost] embedding compute null: model={} tokens={}", model, tokens);
            return;
        }
        log.debug("[LlmCost] embedding cost 写入: model={} total={}", model, cost.totalText());
        context.addHighCardinalityKeyValue(KeyValue.of(AttributeKeys.GEN_AI_USAGE_COST, cost.totalText()));
        context.addHighCardinalityKeyValue(KeyValue.of(AttributeKeys.GEN_AI_USAGE_COST_INPUT, cost.inputText()));
    }

    private String chatRequestModel(ChatModelObservationContext context) {
        try {
            if (context.getRequest() != null && context.getRequest().getOptions() != null) {
                return context.getRequest().getOptions().getModel();
            }
        } catch (Throwable ignored) {
            // 请求侧结构随版本变化，取不到就放弃成本计算
        }
        return null;
    }

    private String embeddingRequestModel(EmbeddingModelObservationContext context) {
        try {
            if (context.getRequest() != null && context.getRequest().getOptions() != null) {
                return context.getRequest().getOptions().getModel();
            }
        } catch (Throwable ignored) {
            // 同上
        }
        return null;
    }
}
