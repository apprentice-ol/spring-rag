package com.jjx.ai.llmobservability.autoconfigure.springai;

import com.jjx.ai.llmobservability.autoconfigure.TelemetryProperties;
import com.jjx.ai.llmobservability.observation.llm.LlmPricing;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Spring AI 可选适配：依赖 {@code org.springframework.ai:spring-ai-model}，宿主未引入时整类跳过。
 *
 * <p>拆成独立自动配置类，主配置类 {@code TelemetryAutoConfiguration} 的方法签名不再引用
 * Spring AI 类型，{@code @ConditionalOnMissingBean} 的返回类型推断不会因缺失类反射失败。</p>
 *
 * <p>类级条件用 {@code name} 而非类引用：直接注册配置类的路径（如测试 runner）以反射读取注解时，
 * 类引用会尝试加载缺失类型导致条件无法正确跳过；按名字判断在 ASM 与反射两条路径下都可靠。</p>
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.springframework.ai.chat.observation.ChatModelObservationContext")
public class SpringAiTelemetryAutoConfiguration {

    /**
     * Spring AI 存在时激活：把会话/pipeline 关联字段挂到 Spring AI 原生 gen_ai span。
     * 无 Spring AI 时该配置类整类跳过，不影响核心能力。
     */
    @Bean
    @ConditionalOnMissingBean(SpringAiConversationObservationFilter.class)
    public SpringAiConversationObservationFilter springAiConversationObservationFilter() {
        return new SpringAiConversationObservationFilter();
    }

    /**
     * Spring AI 1.1.x 的 completion 只写日志不写 span 属性，这里在 stop 时补写 gen_ai.completion。
     * 作为 ObservationHandler bean 由 Spring Boot 自动注册到 ObservationRegistry。
     */
    @Bean
    @ConditionalOnMissingBean(ChatModelCompletionObservationHandler.class)
    public ChatModelCompletionObservationHandler chatModelCompletionObservationHandler() {
        return new ChatModelCompletionObservationHandler();
    }

    /**
     * embedding observation 同样不写 IO：onStart 补 gen_ai.prompt（嵌入的文本）、
     * onStop 补 gen_ai.completion（向量形状摘要），让检索链路上的嵌入步骤可回看。
     */
    @Bean
    @ConditionalOnMissingBean(EmbeddingObservationHandler.class)
    public EmbeddingObservationHandler embeddingObservationHandler() {
        return new EmbeddingObservationHandler();
    }

    /**
     * LLM/embedding 成本估算：filter 在 stop 阶段先于 OTel 导出 handler 执行，keyValue 可落 span
     * （handler 后加的键因顺序问题不进 span，2026-10-12 实测）。
     */
    @Bean
    @ConditionalOnMissingBean(LlmCostObservationFilter.class)
    public LlmCostObservationFilter llmCostObservationFilter(TelemetryProperties telemetryProperties) {
        return new LlmCostObservationFilter(new LlmPricing(telemetryProperties.getPricing()));
    }
}