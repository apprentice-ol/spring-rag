package com.nageoffer.ai.rag.config.telemetry;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import java.util.List;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * 把 ChatModel 调用的 prompt / completion 作为 OTel 标准语义属性（gen_ai.prompt / gen_ai.completion）写入。
 * 这是与后端无关的 OpenTelemetry GenAI 语义约定：Langfuse 等支持 OTel 语义约定的后端据此填充
 * generation 的 input/output 字段，换后端无需改应用。
 *
 * <p><b>实现遵循 Langfuse 官方 Spring AI 集成文档</b>：
 * <a href="https://langfuse.com/integrations/frameworks/spring-ai">Integrating Langfuse with Spring AI</a>。
 * 官方明确：log-prompt/log-completion 只在 observation context 内采集数据，<b>不会</b>自动加成 span attribute，
 * 必须本 filter 把内容作为 gen_ai.prompt / gen_ai.completion attribute 写出，否则 Langfuse generation 的
 * input/output 为 null。</p>
 *
 * <p><b>注意 key</b>：必须是 {@code gen_ai.prompt} / {@code gen_ai.completion}（Langfuse 读这俩），不是
 * input/output——之前用 input/output 导致 Langfuse 不识别。</p>
 *
 * <p>本 filter 是唯一与 Spring AI 类型耦合的埋点（{@code ChatModelObservationContext}），
 * 由 {@code rag.observability.llm.content-attributes} 开关控制；关闭后仅失去问答原文属性，trace 结构不受影响。
 *
 * <p><b>性能</b>：用 String.join 轻量拼接（不用 Gson 反射），catch Throwable 兜底，避免在 reactor 线程阻塞。
 * 仅作用于 ChatModelObservationContext，不影响其他 observation。</p>
 */
@Component
@ConditionalOnProperty(prefix = "rag.observability.llm", name = "content-attributes", havingValue = "true", matchIfMissing = true)
public class ChatModelCompletionContentObservationFilter implements ObservationFilter {

    private static final String KEY_PROMPT = "gen_ai.prompt";
    private static final String KEY_COMPLETION = "gen_ai.completion";

    @Override
    public Observation.Context map(Observation.Context context) {
        if (!(context instanceof ChatModelObservationContext ctx)) {
            return context;
        }
        try {
            // input：prompt（request 构造时即非 null）
            List<String> prompts = processPrompts(ctx);
            if (!prompts.isEmpty()) {
                ctx.addHighCardinalityKeyValue(KeyValue.of(KEY_PROMPT, truncate(String.join("\n", prompts))));
            }
            // output：completion（仅 stop 阶段 response 已注入时才有）
            List<String> completions = processCompletion(ctx);
            if (!completions.isEmpty()) {
                ctx.addHighCardinalityKeyValue(KeyValue.of(KEY_COMPLETION, truncate(String.join("\n", completions))));
            }
        } catch (Throwable ignored) {
            // 观察 filter 绝不能影响 observation 生命周期（连 Error 也吞）
        }
        return ctx;
    }

    private List<String> processPrompts(ChatModelObservationContext ctx) {
        if (CollectionUtils.isEmpty(ctx.getRequest().getInstructions())) {
            return List.of();
        }
        return ctx.getRequest().getInstructions().stream()
                .map(Message::getText)
                .filter(StringUtils::hasText)
                .toList();
    }

    private List<String> processCompletion(ChatModelObservationContext ctx) {
        ChatResponse resp = ctx.getResponse();
        if (resp == null || CollectionUtils.isEmpty(resp.getResults())) {
            return List.of();
        }
        return resp.getResults().stream()
                .map(Generation::getOutput)
                .filter(output -> StringUtils.hasText(output.getText()))
                .map(Message::getText)
                .toList();
    }

    /** 超长 prompt/completion 截断到 span attribute 安全上限，防撑爆 span（与全口径 {@link StepSpan#MAX_SPAN_IO} 一致）。 */
    private static String truncate(String s) {
        return s.length() <= StepSpan.MAX_SPAN_IO ? s : s.substring(0, StepSpan.MAX_SPAN_IO) + "…[truncated]";
    }
}
