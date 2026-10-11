package com.jjx.ai.llmobservability.backends.openobserve;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * collector 关闭时，把 OpenObserve 直连端点桥接到 Spring Boot 的 OTLP logs/metrics 配置。
 *
 * <p>trace 直连由 {@link OpenObserveOtlpAutoConfiguration} 提供 exporter；
 * logs/metrics 在这里桥接为 {@code management.otlp.logging.endpoint} 和
 * {@code management.otlp.metrics.export.url}。</p>
 */
public class OpenObserveEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String PROPERTY_SOURCE_NAME = "telemetry-openobserve-direct-bridge";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        if (collectorEnabled(env)) {
            return;
        }

        Map<String, Object> bridge = new HashMap<>();
        String base = first(env,
                "telemetry.openobserve.otlp-base-url",
                "telemetry.openobserve.url");
        String logs = first(env, "telemetry.openobserve.otlp-logs-endpoint");
        String metrics = first(env, "telemetry.openobserve.otlp-metrics-endpoint");

        if (logs == null) {
            logs = appendPath(base, "/v1/logs");
        }
        if (metrics == null) {
            metrics = appendPath(base, "/v1/metrics");
        }

        putIfAbsent(env, bridge, "management.otlp.logging.endpoint", logs);
        putIfAbsent(env, bridge, "management.otlp.metrics.export.url", metrics);

        String username = env.getProperty("telemetry.openobserve.username");
        String password = env.getProperty("telemetry.openobserve.password");
        String auth = basicAuth(username, password);
        if (auth != null) {
            putIfAbsent(env, bridge, "management.otlp.logging.headers.Authorization", auth);
            putIfAbsent(env, bridge, "management.otlp.metrics.export.headers.Authorization", auth);
        }

        String stream = env.getProperty("telemetry.openobserve.stream");
        if (hasText(stream)) {
            putIfAbsent(env, bridge, "management.otlp.logging.headers.stream-name", stream);
        }

        if (!bridge.isEmpty()) {
            env.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, bridge));
            System.out.println("[telemetry-openobserve] 已桥接直连 logs/metrics -> management : " + bridge.keySet());
        }
    }

    private boolean collectorEnabled(ConfigurableEnvironment env) {
        return Boolean.parseBoolean(env.getProperty("telemetry.collector.enabled", "true"));
    }

    private String first(ConfigurableEnvironment env, String... keys) {
        for (String key : keys) {
            String value = env.getProperty(key);
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private void putIfAbsent(ConfigurableEnvironment env, Map<String, Object> out, String key, String value) {
        if (hasText(value) && !out.containsKey(key) && env.getProperty(key) == null) {
            out.put(key, value);
        }
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
