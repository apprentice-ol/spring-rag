package com.jjx.ai.llmobservability.autoconfigure.springai;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * 把 {@code telemetry.springai.*} 内容捕获开关桥接到 Spring AI 原生观察配置。
 *
 * <p>宿主只配置一个 telemetry 命名空间即可；若已显式配置 spring.ai.* 则不被覆盖。</p>
 */
public class SpringAiTelemetryEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String PROPERTY_SOURCE_NAME = "telemetry-spring-ai-bridge";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        Map<String, Object> bridge = new HashMap<>();
        bridgeIfAbsent("telemetry.springai.log-prompt", "spring.ai.chat.observations.log-prompt", env, bridge);
        bridgeIfAbsent("telemetry.springai.log-completion", "spring.ai.chat.observations.log-completion", env, bridge);
        if (!bridge.isEmpty()) {
            env.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, bridge));
        }
    }

    private void bridgeIfAbsent(String from, String to, ConfigurableEnvironment env, Map<String, Object> out) {
        String value = env.getProperty(from);
        if (value != null && env.getProperty(to) == null) {
            out.put(to, value);
        }
    }
}
