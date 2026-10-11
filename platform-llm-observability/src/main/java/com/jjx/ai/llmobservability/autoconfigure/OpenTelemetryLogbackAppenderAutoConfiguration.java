package com.jjx.ai.llmobservability.autoconfigure;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 把 Spring Boot 的 {@link OpenTelemetry} 实例安装给 logback 的 OpenTelemetryAppender。
 *
 * <p>这是通用 OTLP Logs 能力，放在 llm-observability 核心模块中，任何宿主只要引入了
 * {@code opentelemetry-logback-appender-1.0}，都能直接使用。</p>
 */
@AutoConfiguration
@ConditionalOnClass(OpenTelemetryAppender.class)
public class OpenTelemetryLogbackAppenderAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OpenTelemetryLogbackAppenderInstaller openTelemetryLogbackAppenderInstaller(
            OpenTelemetry openTelemetry) {
        return new OpenTelemetryLogbackAppenderInstaller(openTelemetry);
    }

    static class OpenTelemetryLogbackAppenderInstaller implements InitializingBean {

        private final OpenTelemetry openTelemetry;

        OpenTelemetryLogbackAppenderInstaller(OpenTelemetry openTelemetry) {
            this.openTelemetry = openTelemetry;
        }

        @Override
        public void afterPropertiesSet() {
            OpenTelemetryAppender.install(openTelemetry);
        }
    }
}
