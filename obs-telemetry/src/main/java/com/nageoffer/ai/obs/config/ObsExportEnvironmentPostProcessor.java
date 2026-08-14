package com.nageoffer.ai.obs.config;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * 把 {@code obs.export.*} 桥接到 Spring Boot actuator 的 OTel exporter 配置（{@code management.*}）。
 *
 * <p>应用在 {@code obs.*} 统一配 trace 发送（{@code obs.export.otlp.endpoint} / {@code obs.export.sampling.probability}），
 * 本处理器在启动最早阶段把它转发到 {@code management.otlp.tracing.endpoint} / {@code management.tracing.sampling.probability}，
 * 让 Spring Boot 的 OTel autoconfig 读到——应用只配 {@code obs.*}，不直接碰 management。</p>
 *
 * <p><b>仅在用户未显式配 management.* 时转发</b>（不覆盖框架直配）。</p>
 *
 * <p><b>注册方式</b>：通过 {@code META-INF/spring.factories} 注册（Spring Boot 3.x 的 EnvironmentPostProcessor
 * 仍用 spring.factories，不是 AutoConfiguration.imports——这是之前 trace 不发的根因）。</p>
 */
public class ObsExportEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String PROPERTY_SOURCE_NAME = "obs-export-bridge";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        Map<String, Object> bridge = new HashMap<>();
        bridge("obs.export.otlp.endpoint", "management.otlp.tracing.endpoint", env, bridge);
        bridge("obs.export.sampling.probability", "management.tracing.sampling.probability", env, bridge);
        if (!bridge.isEmpty()) {
            env.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, bridge));
            System.out.println("[obs-bridge] 已桥接 obs.export.* -> management.* : " + bridge);
        } else {
            System.out.println("[obs-bridge] 未桥接（obs.export.* 未配，或 management.* 已直配）");
        }
    }

    private void bridge(String from, String to, ConfigurableEnvironment env, Map<String, Object> out) {
        String value = env.getProperty(from);
        if (value != null && env.getProperty(to) == null) {
            out.put(to, value);
        }
    }
}
