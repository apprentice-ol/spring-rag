package com.nageoffer.ai.rag.config.telemetry;

import io.opentelemetry.api.trace.Span;
import java.util.HashMap;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.core.Ordered;


/**
 * LLM 调用跟踪 Advisor：在每次 ChatClient 调用前后记录模型参数与 token 用量。
 *
 * <p>实现 Spring AI 1.1 的 {@link BaseAdvisor}，只重写 before/after；流式调用时
 * {@code BaseAdvisor.adviseStream} 默认实现只在带 finishReason 的终止 chunk 触发一次 after，
 * 因此流式场景的 usage 也能接住。</p>
 *
 * <p>模型参数/用量写入 MDC（{@code OpenObserveAppender} 平铺为字段，可按 llm_model / llm_total_tokens
 * 过滤），并各发一条结构化日志 {@code llm.request} / {@code llm.response}。MDC 主动写入，
 * 不依赖 Reactor 跨线程自动传播。</p>
 *
 * <p>只读不改 request/response，挂上不影响既有 LLM 调用语义。</p>
 */
public class LlmTraceAdvisor implements BaseAdvisor {
    /** 调用方角色：rag（回答/闲聊）或 ingestion（改写/意图/入库增强） */
    private final String role;
    /** 全局默认文本模型，request options 未显式指定 model 时兜底 */
    private final String defaultModel;

    public LlmTraceAdvisor(String role, String defaultModel) {
        this.role = role;
        this.defaultModel = defaultModel;
    }

    @Override
    public String getName() {
        return "llm-trace";
    }

    /** 最先执行 before、最后执行 after：保证 step span 内能观测到完整 LLM 调用。 */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1000;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, @NotNull AdvisorChain chain) {
        ChatOptions options = request.prompt().getOptions();
        String model = options != null && options.getModel() != null ? options.getModel() : defaultModel;
        Double temperature = options != null ? options.getTemperature() : null;
        Integer maxTokens = options != null ? options.getMaxTokens() : null;

        MDC.put("llm_role", role);
        MDC.put("llm_model", model);
        if (temperature != null) {
            MDC.put("llm_temperature", String.valueOf(temperature));
        }
        if (maxTokens != null) {
            MDC.put("llm_max_tokens", String.valueOf(maxTokens));
        }

        int messageCount = request.prompt().getInstructions().size();
        // Map.of 不允许 null value，temperature/max_tokens 未显式设置时为 null → 用 HashMap 兜底
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("role", role);
        attrs.put("model", model);
        attrs.put("temperature", temperature);
        attrs.put("max_tokens", maxTokens);
        attrs.put("message_count", messageCount);
        // prompt 摘要：落 llm-trace span 的 input attribute（OpenObserve trace Input 面板）+ llm.request 日志 data
        Object promptSummary = Summarizer.summarize(request.prompt().getInstructions());
        attrs.put("messages", promptSummary);
        try {
            Span.current().setAttribute("input", Summarizer.toJsonTruncated(promptSummary, StepSpan.MAX_SPAN_IO));
        } catch (Exception ignored) {
            // 无当前 span 或设置失败，忽略（结构化日志仍已记录）
        }
        StructuredLog.emit("llm.request", attrs);
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, @NotNull AdvisorChain chain) {
        Usage usage = null;
        if (response.chatResponse() != null) {
            usage = response.chatResponse().getMetadata().getUsage();
        }
        if (usage != null) {
            if (usage.getPromptTokens() != null) {
                MDC.put("llm_prompt_tokens", String.valueOf(usage.getPromptTokens()));
            }
            if (usage.getCompletionTokens() != null) {
                MDC.put("llm_completion_tokens", String.valueOf(usage.getCompletionTokens()));
            }
            if (usage.getTotalTokens() != null) {
                MDC.put("llm_total_tokens", String.valueOf(usage.getTotalTokens()));
            }
        }
        // 同上：usage 为 null 时 token 全为 null，Map.of 会 NPE
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("role", role);
        attrs.put("prompt_tokens", usage == null ? null : usage.getPromptTokens());
        attrs.put("completion_tokens", usage == null ? null : usage.getCompletionTokens());
        attrs.put("total_tokens", usage == null ? null : usage.getTotalTokens());
        StructuredLog.emit("llm.response", attrs);
        return response;
    }
}
