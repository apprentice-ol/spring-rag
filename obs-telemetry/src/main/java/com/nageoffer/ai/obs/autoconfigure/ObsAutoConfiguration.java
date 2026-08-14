package com.nageoffer.ai.obs.autoconfigure;

import com.nageoffer.ai.obs.observation.ObsTemplate;
import com.nageoffer.ai.obs.observation.ObservationPipeline;
import com.nageoffer.ai.obs.observation.aspect.ObservedConversationAspect;
import com.nageoffer.ai.obs.observation.aspect.ObservedStepAspect;
import com.nageoffer.ai.obs.observation.exporter.ObservationExporter;
import com.nageoffer.ai.obs.observation.exporter.SpanAttributeExporter;
import com.nageoffer.ai.obs.observation.exporter.StructuredLogExporter;
import com.nageoffer.ai.obs.observation.llm.GenAiLlmTraceHandler;
import com.nageoffer.ai.obs.observation.llm.LlmTraceHandler;
import com.nageoffer.ai.obs.observation.processor.ObservationProcessor;
import com.nageoffer.ai.obs.observation.processor.SpanIoLimitProcessor;
import com.nageoffer.ai.obs.observation.processor.SummarizeProcessor;
import com.nageoffer.ai.obs.observation.propagation.ContextPropagationConfiguration;
import com.nageoffer.ai.obs.autoconfigure.springai.SpringAiConversationObservationFilter;
import com.nageoffer.ai.obs.autoconfigure.springai.SpringAiObsProperties;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.OpenTelemetry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * obs-telemetry 的 Spring Boot 自动装配入口（引入依赖即生效，无需宿主 @ComponentScan）。
 *
 * <p><b>装配清单（全部显式 @Bean，不整包扫描）</b>：事件管线 {@link ObservationPipeline}
 * （内置 摘要→截断 processor 链 + span attribute→结构化日志 exporter 链）、观测门面 {@link ObsTemplate}、
 * 两个注解切面、micrometer context-propagation 注册。宿主扩展 processor/exporter 只需注册为 bean，
 * 会被 {@link ObservationPipeline} 的构造注入自动收集并按 @Order 排序。</p>
 *
 * <p><b>宿主前置条件（缺失时优雅降级，不阻断启动）</b>：步骤 span 经 micrometer Observation → OTel，
 * 完整链路需要宿主自带 tracing 桥——{@code io.micrometer:micrometer-tracing-bridge-otel}（Spring Boot
 * 经 {@code spring-boot-starter-actuator} + OTLP exporter 自动装配出 {@link OpenTelemetry} bean）。
 * {@link OpenTelemetry} bean 缺失时门面降级 noop（{@code kind=ROOT} 的独立 trace 不再上报，
 * Observation 步骤 span 一并失效），仅打 warn。</p>
 *
 * <p>本模块不依赖任何 LLM 框架；{@link LlmTraceHandler} 默认实现是框架无关的 GenAI 语义记录器，
 * Spring AI 适配已内置（可选依赖 + {@code @ConditionalOnClass}），其它 LLM 框架由宿主应用
 * 提供对应的 {@code ObservationFilter} 适配器即可。</p>
 */
@AutoConfiguration
@ConditionalOnClass(ObservationRegistry.class)
@EnableConfigurationProperties({ObsProperties.class, SpringAiObsProperties.class})
public class ObsAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ObsAutoConfiguration.class);

    // ==================== 转：processor 链（内置两环） ====================

    @Bean
    public SummarizeProcessor summarizeProcessor() {
        return new SummarizeProcessor();
    }

    @Bean
    public SpanIoLimitProcessor spanIoLimitProcessor() {
        return new SpanIoLimitProcessor();
    }

    // ==================== 发：exporter 链（内置两出口） ====================

    @Bean
    public SpanAttributeExporter spanAttributeExporter() {
        return new SpanAttributeExporter();
    }

    @Bean
    public StructuredLogExporter structuredLogExporter() {
        return new StructuredLogExporter();
    }

    // ==================== 管线与传播 ====================

    /** 事件管线：注入容器中全部 processor/exporter bean（含宿主扩展），按 @Order 排序。 */
    @Bean
    public ObservationPipeline observationPipeline(List<ObservationProcessor> processors,
                                                   List<ObservationExporter> exporters) {
        return new ObservationPipeline(processors, exporters);
    }

    /** micrometer context-propagation（MDC/OTel/对话上下文跨线程透传）+ Reactor 自动传播（初始化期执行）。 */
    @Bean
    public ContextPropagationConfiguration contextPropagationConfiguration() {
        return new ContextPropagationConfiguration();
    }

    // ==================== 门面与切面 ====================

    /**
     * 观测门面。创建时先把 {@code obs.limits.*} 落到 SpanIoLimits/Summarizer 全局值（开任何 span 前）。
     *
     * @param registry      Micrometer 观察注册表
     * @param openTelemetry OTel API（由宿主 tracing 桥提供；缺失降级 noop 并 warn）
     * @param pipeline      事件处理与落地管线
     * @param properties    obs 配置
     */
    @Bean
    @ConditionalOnMissingBean
    public ObsTemplate obsTemplate(ObservationRegistry registry, ObjectProvider<OpenTelemetry> openTelemetry,
                                   ObservationPipeline pipeline, ObsProperties properties) {
        properties.getLimits().apply();
        OpenTelemetry otel = openTelemetry.getIfAvailable();
        if (otel == null) {
            log.warn("[obs] 容器中无 OpenTelemetry bean（宿主缺 tracing 桥？需要 micrometer-tracing-bridge-otel"
                    + " + actuator 自动装配）——openTrace/kind=ROOT 降级 noop，不会产生独立 trace");
            otel = OpenTelemetry.noop();
        }
        return new ObsTemplate(registry, otel, pipeline, properties.getTracerName());
    }

    /** {@code @ObservedConversation} 切面（入口对话上下文）。 */
    @Bean
    @ConditionalOnMissingBean
    public ObservedConversationAspect observedConversationAspect(ObsTemplate obsTemplate) {
        return new ObservedConversationAspect(obsTemplate);
    }

    /** {@code @ObservedStep} 切面（步骤自动埋点）。 */
    @Bean
    @ConditionalOnMissingBean
    public ObservedStepAspect observedStepAspect(ObsTemplate obsTemplate) {
        return new ObservedStepAspect(obsTemplate);
    }

    // ==================== LLM（框架无关默认 + Spring AI 可选适配） ====================

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
    public SpringAiConversationObservationFilter springAiConversationObservationFilter() {
        return new SpringAiConversationObservationFilter();
    }
}
