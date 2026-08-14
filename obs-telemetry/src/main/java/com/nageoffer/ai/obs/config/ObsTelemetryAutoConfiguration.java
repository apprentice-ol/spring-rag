package com.nageoffer.ai.obs.config;

import com.nageoffer.ai.obs.Telemetry;
import com.nageoffer.ai.obs.llm.GenAiLlmTraceHandler;
import com.nageoffer.ai.obs.llm.LlmTraceHandler;
import com.nageoffer.ai.obs.processor.TracePipeline;
import com.nageoffer.ai.obs.springai.SpringAiConversationCorrelationFilter;
import com.nageoffer.ai.obs.springai.SpringAiObsProperties;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.OpenTelemetry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.ai.chat.observation.ChatModelObservationContext;

/**
 * obs-telemetry 的 Spring Boot 自动装配入口。
 *
 * <p>核心 bean：{@link Telemetry}（门面）+ {@link TracePipeline}（事件处理与落地）。TracePipeline 由
 * {@code @Component} 收集 processor/exporter，统一负责 span attribute 与结构化日志两个出口。</p>
 *
 * <p>本模块不依赖任何 LLM 框架；{@link LlmTraceHandler} 的默认实现是框架无关的 GenAI 语义记录器，
 * Spring AI 适配已内置（可选依赖 + {@code @ConditionalOnClass}），其它 LLM 框架由宿主应用
 * 提供对应的 {@code ObservationFilter} 适配器即可。</p>
 */
@AutoConfiguration
@ConditionalOnClass(ObservationRegistry.class)
@EnableConfigurationProperties({ObsProperties.class, SpringAiObsProperties.class})
@ComponentScan("com.nageoffer.ai.obs")
public class ObsTelemetryAutoConfiguration {

    /**
     * 创建观测门面。
     *
     * @param registry     Micrometer 观测注册表
     * @param openTelemetry OTel API（由宿主应用 tracing bridge 提供）
     * @param pipeline     事件处理与落地管线
     * @param properties   obs 配置
     * @return Telemetry 门面
     */
    @Bean
    @ConditionalOnMissingBean
    public Telemetry telemetry(ObservationRegistry registry, OpenTelemetry openTelemetry,
                               TracePipeline pipeline, ObsProperties properties) {
        return new Telemetry(registry, openTelemetry, pipeline, properties);
    }

    /**
     * 默认 LLM 观测记录器：写 OTel GenAI 语义（gen_ai.*）。
     * 应用可提供自定义 {@link LlmTraceHandler} bean 覆盖。
     */
    @Bean
    @ConditionalOnMissingBean
    public LlmTraceHandler llmTraceHandler() {
        return new GenAiLlmTraceHandler();
    }

    /**
     * Spring AI 存在时激活：把会话/pipeline 关联字段挂到 Spring AI 原生 gen_ai span。
     * 无 Spring AI 时该 bean 自动跳过，不影响核心能力。
     */
    @Bean
    @ConditionalOnClass(ChatModelObservationContext.class)
    @ConditionalOnMissingBean
    public SpringAiConversationCorrelationFilter springAiConversationCorrelationFilter() {
        return new SpringAiConversationCorrelationFilter();
    }
}
