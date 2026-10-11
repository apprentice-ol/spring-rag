package com.jjx.ai.llmobservability.autoconfigure;

import com.jjx.ai.llmobservability.autoconfigure.springai.SpringAiTelemetryProperties;
import com.jjx.ai.llmobservability.observation.TelemetryTemplate;
import com.jjx.ai.llmobservability.observation.ObservationPipeline;
import com.jjx.ai.llmobservability.observation.aspect.TelemetryConversationAspect;
import com.jjx.ai.llmobservability.observation.aspect.TelemetryStepAspect;
import com.jjx.ai.llmobservability.observation.exporter.*;
import com.jjx.ai.llmobservability.observation.filter.TelemetryFilter;
import com.jjx.ai.llmobservability.observation.filter.TelemetryFilterChain;
import com.jjx.ai.llmobservability.observation.llm.GenAiLlmTraceHandler;
import com.jjx.ai.llmobservability.observation.llm.LlmTraceHandler;
import com.jjx.ai.llmobservability.observation.processor.ObservationProcessor;
import com.jjx.ai.llmobservability.observation.filter.TelemetryFilterSupport;
import com.jjx.ai.llmobservability.observation.processor.SpanIoLimitProcessor;
import com.jjx.ai.llmobservability.observation.processor.SummarizeProcessor;
import com.jjx.ai.llmobservability.observation.propagation.ContextPropagationConfiguration;
import com.jjx.ai.llmobservability.observation.support.AttributeKeys;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.OpenTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * llm-observability 的 Spring Boot 自动装配入口（引入依赖即生效，无需宿主 @ComponentScan）。
 *
 * <p><b>装配清单（全部显式 @Bean，不整包扫描）</b>：事件管线 {@link ObservationPipeline}
 * （过滤器链 + 摘要→截断 processor 链 + span attribute→结构化日志 exporter 链）、观测门面 {@link TelemetryTemplate}、
 * 两个注解切面、micrometer context-propagation 注册。宿主扩展 processor/exporter 只需注册为 bean，
 * 会被 {@link ObservationPipeline} 的构造注入自动收集并按 @Order 排序。</p>
 *
 * <p><b>宿主前置条件（缺失时优雅降级，不阻断启动）</b>：步骤 span 经 micrometer Observation → OTel，
 * 完整链路需要宿主自带 tracing 桥——{@code io.micrometer:micrometer-tracing-bridge-otel}（Spring Boot
 * 经 {@code spring-boot-starter-actuator} + OTLP exporter 自动装配出 {@link OpenTelemetry} bean）。
 * {@link OpenTelemetry} bean 缺失时门面降级 noop（{@code kind=ROOT} 的独立 trace 不再上报，
 * Observation 步骤 span 一并失效），仅打 warn。</p>
 *
 * <p>本模块不依赖任何 LLM 框架；{@link LlmTraceHandler} 默认实现是框架无关的 GenAI 语义记录器。
 * 需要可选依赖的增强（OTel SDK 的 SpanProcessor、Spring AI 适配）都拆在独立自动配置类中，
 * 用类级 {@code @ConditionalOnClass} 保护，缺失时整类跳过，不阻断启动。</p>
 */
@AutoConfiguration
@ConditionalOnClass(ObservationRegistry.class)
@EnableConfigurationProperties({TelemetryProperties.class, SpringAiTelemetryProperties.class, TelemetryFilterProperties.class})
public class TelemetryAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TelemetryAutoConfiguration.class);

    // ==================== 转：过滤器链 + processor 链 ====================

    /** 观测事件过滤器链（SPI 执行器）：收集全部 {@code TelemetryFilter} bean，按 @Order 排序。 */
    @Bean
    @ConditionalOnMissingBean(TelemetryFilterChain.class)
    public TelemetryFilterChain telemetryFilterChain(ObjectProvider<TelemetryFilter> filters) {
        return new TelemetryFilterChain(filters.orderedStream().toList());
    }

    /** 把过滤器链桥接到静态门面（TelemetryStructuredLog 直发路径），启动期安装一次。 */
    @Bean
    @ConditionalOnMissingBean(TelemetryFilterBridge.class)
    public TelemetryFilterBridge telemetryFilterBridge(TelemetryFilterChain chain,
                                                       TelemetryFilterProperties properties) {
        return new TelemetryFilterBridge(chain, properties.isEnabled());
    }

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
    public SpanAttributeExporter spanAttributeExporter(ObjectProvider<SpanAttributeKeyMapper> keyMappers) {
        return new SpanAttributeExporter(keyMappers.orderedStream().toList());
    }

    @Bean
    public StructuredLogExporter structuredLogExporter() {
        return new StructuredLogExporter();
    }

    /**
     * Metrics 支柱：STEP_OUTPUT 打步骤耗时 Timer（{ns}.step.duration，无 OTel 标准名的框架自有指标）。
     * 宿主带 micrometer-core（actuator）时注册，MeterRegistry bean 缺失时 exporter no-op。
     */
    @Bean
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnMissingBean(MetricsExporter.class)
    public MetricsExporter metricsExporter(ObjectProvider<MeterRegistry> meterRegistry) {
        return new MetricsExporter(meterRegistry.getIfAvailable());
    }

    // ==================== 管线与传播 ====================

    /** 事件管线：注入容器中全部 processor/exporter bean（含宿主扩展），按 @Order 排序。 */
    @Bean
    public ObservationPipeline observationPipeline(List<ObservationProcessor> processors,
                                                   List<ObservationExporter> exporters,
                                                   ObjectProvider<TelemetryFilterChain> filterChain,
                                                   TelemetryFilterProperties properties) {
        return new ObservationPipeline(processors, exporters,
                filterChain.getIfAvailable(), properties.isEnabled());
    }

    /** micrometer context-propagation（MDC/OTel/对话上下文跨线程透传）+ Reactor 自动传播（初始化期执行）。 */
    @Bean
    public ContextPropagationConfiguration contextPropagationConfiguration() {
        return new ContextPropagationConfiguration();
    }

    // ==================== 门面与切面 ====================

    /**
     * 观测门面。创建时先把 {@code telemetry.limits.*} 与属性命名空间落到全局值（开任何 span 前）。
     *
     * @param registry      Micrometer 观察注册表
     * @param openTelemetry OTel API（由宿主 tracing 桥提供；缺失降级 noop 并 warn）
     * @param pipeline      事件处理与落地管线
     * @param properties    telemetry 配置
     * @param env           Spring 环境（命名空间缺省取 spring.application.name）
     */
    @Bean
    @ConditionalOnMissingBean(TelemetryTemplate.class)
    public TelemetryTemplate obsTemplate(ObservationRegistry registry, ObjectProvider<OpenTelemetry> openTelemetry,
                                   ObservationPipeline pipeline, TelemetryProperties properties,
                                   org.springframework.core.env.Environment env) {
        properties.getLimits().apply();
        String ns = properties.getAttributeNamespace();
        if (ns == null || ns.isBlank()) {
            ns = env.getProperty("spring.application.name");
        }
        AttributeKeys.configureNamespace(ns);
        OpenTelemetry otel = openTelemetry.getIfAvailable();
        if (otel == null) {
            log.warn("[telemetry] 容器中无 OpenTelemetry bean（宿主缺 tracing 桥？需要 micrometer-tracing-bridge-otel"
                    + " + actuator 自动装配）——openTrace/kind=ROOT 降级 noop，不会产生独立 trace");
            otel = OpenTelemetry.noop();
        }
        return new TelemetryTemplate(registry, otel, pipeline, properties.getTracerName());
    }

    /** {@code @TelemetryConversation} 切面（入口对话上下文）。 */
    @Bean
    @ConditionalOnMissingBean(TelemetryConversationAspect.class)
    public TelemetryConversationAspect observedConversationAspect(TelemetryTemplate obsTemplate) {
        return new TelemetryConversationAspect(obsTemplate);
    }

    /** {@code @TelemetryStep} 切面（步骤自动埋点）。 */
    @Bean
    @ConditionalOnMissingBean(TelemetryStepAspect.class)
    public TelemetryStepAspect observedStepAspect(TelemetryTemplate obsTemplate) {
        return new TelemetryStepAspect(obsTemplate);
    }

    // ==================== LLM（框架无关默认 + Spring AI 可选适配） ====================

    /**
     * 默认 LLM 观测记录器：写 OTel GenAI 语义（gen_ai.*）。
     * 应用可提供自定义 {@link LlmTraceHandler} bean 覆盖。
     */
    @Bean
    @ConditionalOnMissingBean(LlmTraceHandler.class)
    public LlmTraceHandler llmTraceHandler() {
        return new GenAiLlmTraceHandler();
    }

    /** 静态桥安装器：让 {@code TelemetryStructuredLog.emit} 直发路径也走同一过滤器链。 */
    static class TelemetryFilterBridge implements InitializingBean {

        private final TelemetryFilterChain chain;
        private final boolean enabled;

        TelemetryFilterBridge(TelemetryFilterChain chain, boolean enabled) {
            this.chain = chain;
            this.enabled = enabled;
        }

        @Override
        public void afterPropertiesSet() {
            TelemetryFilterSupport.install(chain, enabled);
        }
    }
}