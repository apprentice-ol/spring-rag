package com.jjx.ai.llmobservability.autoconfigure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 观测事件过滤器链配置（前缀 {@code telemetry.filter}）。
 *
 * <p><b>职责</b>：只控制过滤器链的开关。过滤规则不在本项目内置，
 * 由外部实现 {@code TelemetryFilter} 并注册为 Spring bean 提供。</p>
 */
@Data
@ConfigurationProperties("telemetry.filter")
public class TelemetryFilterProperties {

    /** 是否执行观测事件过滤器链。注册了过滤器但想临时关闭时设为 false。默认 true。 */
    private boolean enabled = true;

    /** 是否把同一过滤器链装到所有 logback appender 之前（覆盖普通/框架日志）。默认 true。 */
    private boolean logbackEnabled = true;
}
