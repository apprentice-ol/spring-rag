package com.jjx.ai.llmobservability.backends.langfuse;

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporterBuilder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpTracingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * collector 关闭时，提供直连 Langfuse 的 OTLP trace exporter。
 */
@AutoConfiguration(before = OtlpTracingAutoConfiguration.class)
@ConditionalOnClass(OtlpHttpSpanExporter.class)
@ConditionalOnProperty(name = "telemetry.collector.enabled", havingValue = "false")
@EnableConfigurationProperties(LangfuseProperties.class)
public class LangfuseOtlpAutoConfiguration {

    /**
     * 缺这个头，直传的 OTel 数据在 LangFuse 里最多延迟十分钟才可见（v4 摄取有两条路径，
     * 带头走快路径）。与 Authorization 一样属于「不带也能收，但排查时像丢数据」的隐性契约。
     */
    private static final String INGESTION_VERSION_HEADER = "x-langfuse-ingestion-version";

    @Bean
    @ConditionalOnMissingBean(name = "langfuseDirectOtlpSpanExporter")
    public OtlpHttpSpanExporter langfuseDirectOtlpSpanExporter(LangfuseProperties properties) {
        String endpoint = first(properties.getOtlpTracesEndpoint(),
                appendPath(properties.getUrl(), "/api/public/otel/v1/traces"));
        OtlpHttpSpanExporterBuilder builder = OtlpHttpSpanExporter.builder()
                .setEndpoint(endpoint)
                .setTimeout(Duration.ofSeconds(10))
                .addHeader(INGESTION_VERSION_HEADER, "4");
        String auth = basicAuth(properties.getPublicKey(), properties.getSecretKey());
        if (hasText(auth)) {
            builder.addHeader("Authorization", auth);
        }
        return builder.build();
    }

    private String first(String preferred, String fallback) {
        return hasText(preferred) ? preferred : fallback;
    }

    private String appendPath(String base, String path) {
        if (!hasText(base)) {
            return null;
        }
        String normalized = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return normalized + path;
    }

    private String basicAuth(String username, String password) {
        if (!hasText(username) || password == null) {
            return null;
        }
        String raw = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
