package com.jjx.ai.llmobservability.autoconfigure;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * 把 {@code telemetry.collector.* / telemetry.sampling.*} 桥接到 Spring Boot actuator 的 OTel exporter 配置。
 *
 * <p>这里只负责通用 collector 开关与 OTLP signals 桥接。具体后端（OpenObserve、Langfuse）的
 * 直连逻辑放在各自的适配模块中，避免通用组件依赖 vendor 配置。</p>
 *
 * <p><b>仅在用户未显式配 management.* 时转发</b>（不覆盖框架直配）。</p>
 *
 * <p><b>注册方式</b>：通过 {@code META-INF/spring.factories} 注册（Spring Boot 3.x 的 EnvironmentPostProcessor
 * 仍用 spring.factories，不是 AutoConfiguration.imports——这是之前 trace 不发的根因）。</p>
 */
public class TelemetryEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String PROPERTY_SOURCE_NAME = "telemetry-management-bridge";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        Map<String, Object> bridge = new HashMap<>();
        bridge("telemetry.sampling.probability", "management.tracing.sampling.probability", env, bridge);

        boolean collectorEnabled = getBoolean(env, "telemetry.collector.enabled", true);
        if (collectorEnabled) {
            bridgeCollectorMode(env, bridge);
        }

        if (!bridge.isEmpty()) {
            env.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, bridge));
            System.out.println("[telemetry-bridge] collector=" + collectorEnabled
                    + "，已桥接 -> management : " + bridge.keySet());
        } else {
            System.out.println("[telemetry-bridge] collector=" + collectorEnabled
                    + "，未桥接（telemetry.* 未配，或 management 已直配）");
        }
    }

    private void bridgeCollectorMode(ConfigurableEnvironment env, Map<String, Object> out) {
        String legacyTrace = env.getProperty("telemetry.collector.otlp-endpoint");
        String collectorBase = env.getProperty("telemetry.collector.base-url");
        String traces = first(env,
                "telemetry.collector.traces-endpoint",
                "telemetry.collector.otlp-endpoint");
        String logs = first(env, "telemetry.collector.logs-endpoint");
        String metrics = first(env, "telemetry.collector.metrics-endpoint");

        if (traces == null && collectorBase != null) {
            traces = appendPath(collectorBase, "/v1/traces");
        }
        if (logs == null && legacyTrace != null) {
            logs = sibling(legacyTrace, "/v1/traces", "/v1/logs");
        }
        if (logs == null && collectorBase != null) {
            logs = appendPath(collectorBase, "/v1/logs");
        }
        if (metrics == null && legacyTrace != null) {
            metrics = sibling(legacyTrace, "/v1/traces", "/v1/metrics");
        }
        if (metrics == null && collectorBase != null) {
            metrics = appendPath(collectorBase, "/v1/metrics");
        }

        bridge("telemetry.collector.traces-endpoint", "management.otlp.tracing.endpoint", env, out);
        if (traces != null) {
            putIfAbsent(env, out, "management.otlp.tracing.endpoint", traces);
        }
        if (logs != null) {
            putIfAbsent(env, out, "management.otlp.logging.endpoint", logs);
        }
        if (metrics != null) {
            putIfAbsent(env, out, "management.otlp.metrics.export.url", metrics);
        }

        String collectorAuth = env.getProperty("telemetry.collector.headers.Authorization");
        if (hasText(collectorAuth)) {
            putIfAbsent(env, out, "management.otlp.tracing.headers.Authorization", collectorAuth);
            putIfAbsent(env, out, "management.otlp.logging.headers.Authorization", collectorAuth);
            putIfAbsent(env, out, "management.otlp.metrics.export.headers.Authorization", collectorAuth);
        }
    }

    private void bridge(String from, String to, ConfigurableEnvironment env, Map<String, Object> out) {
        String value = env.getProperty(from);
        if (value != null && env.getProperty(to) == null) {
            out.put(to, value);
        }
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

    private boolean getBoolean(ConfigurableEnvironment env, String key, boolean defaultValue) {
        String value = env.getProperty(key);
        return value == null ? defaultValue : Boolean.parseBoolean(value);
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

    private String sibling(String endpoint, String oldSignal, String newSignal) {
        if (endpoint != null && endpoint.endsWith(oldSignal)) {
            return endpoint.substring(0, endpoint.length() - oldSignal.length()) + newSignal;
        }
        return null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
