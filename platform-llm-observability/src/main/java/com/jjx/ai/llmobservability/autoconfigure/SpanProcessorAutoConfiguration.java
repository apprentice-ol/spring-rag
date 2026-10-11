package com.jjx.ai.llmobservability.autoconfigure;

import com.jjx.ai.llmobservability.observation.exporter.SpanAttributeKeyMapper;
import com.jjx.ai.llmobservability.observation.propagation.BaggageAttributeSpanProcessor;
import com.jjx.ai.llmobservability.observation.propagation.GenAiAttributePropagationSpanProcessor;
import io.opentelemetry.sdk.trace.SpanProcessor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * OTel SDK 增强（SpanProcessor）：依赖 {@code io.opentelemetry:opentelemetry-sdk}，
 * 由宿主通过 tracing 桥/OTLP exporter 提供。SDK 缺失时整类跳过，不阻断启动。
 *
 * <p>拆成独立自动配置类的原因：主配置类 {@link TelemetryAutoConfiguration} 的方法签名不再引用
 * 可选类型，{@code @ConditionalOnMissingBean} 的返回类型推断不会因缺失类反射失败。</p>
 *
 * <p>类级条件用 {@code name} 而非类引用：直接注册配置类的路径（如测试 runner）以反射读取注解时，
 * 类引用会尝试加载缺失类型导致条件无法正确跳过；按名字判断在 ASM 与反射两条路径下都可靠。</p>
 */
@AutoConfiguration
@ConditionalOnClass(name = "io.opentelemetry.sdk.trace.SpanProcessor")
public class SpanProcessorAutoConfiguration {

    /**
     * Baggage → span 属性：trace 内所有 span（含框架建的）统一落 baggage 条目 + 后端映射 key。
     * Spring Boot 自动把 SpanProcessor bean 收进 TracerProvider，与应用出口（collector/直连）无关。
     * {@code telemetry.propagation.baggage-span-attributes=false} 可停用。
     */
    @Bean
    @ConditionalOnProperty(prefix = "telemetry.propagation", name = "baggage-span-attributes",
            havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(SpanProcessor.class)
    public SpanProcessor baggageAttributeSpanProcessor(ObjectProvider<SpanAttributeKeyMapper> keyMappers) {
        return new BaggageAttributeSpanProcessor(keyMappers.orderedStream().toList());
    }

    /**
     * GenAI 关键字段传播：把最内层 LLM generation span 的 model/usage/system 补到 trace 根与入口 step，
     * 让 OpenObserve 的 traces 列表/详情在根 span 也能直接看到这些列。
     */
    @Bean
    public SpanProcessor genAiAttributePropagationSpanProcessor() {
        return new GenAiAttributePropagationSpanProcessor();
    }
}