package com.nageoffer.ai.obs.autoconfigure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * obs 可观测组件的统一配置（前缀 {@code obs}）。
 *
 * <p>应用只配这一个 {@code obs.*} 块即可管理全部可观测性：</p>
 * <ul>
 *   <li>{@link #tracerName} —— 埋点核心（obs 组件本身）。</li>
 *   <li>{@link #collector} —— OTLP Collector 连接；由
 *       {@link ObsEnvironmentPostProcessor} 桥接到 Spring Boot actuator 的 OTel exporter
 *       （{@code management.otlp.tracing.endpoint}）。</li>
 *   <li>{@link #sampling} —— trace 采样率，桥接到 {@code management.tracing.sampling.probability}。</li>
 * </ul>
 *
 * <p>{@code obs.openobserve.*} 由业务侧（rag-core 的 OpenObserveProperties）绑定，本类不含——openobserve 是
 * RAG 业务的日志诊断连接，不属公共组件。</p>
 */
@Data
@ConfigurationProperties("obs")
public class ObsProperties {

    /** OTel tracer 名（openTrace 开无父新 trace 根 span 用），区分服务。 */
    private String tracerName = "obs";

    /** OTLP Collector 连接（应用唯一的 OTLP 出口）。 */
    private Collector collector = new Collector();

    /** trace 采样。 */
    private Sampling sampling = new Sampling();

    @Data
    public static class Collector {
        /**
         * 是否使用 otel-collector。
         * true = 应用只发到 collector，后端由 collector 扇出；
         * false = 应用直连 OpenObserve / Langfuse 的 OTLP 入口。
         */
        private boolean enabled = true;

        /** 兼容旧变量：一般不再直接使用。 */
        private String otlpEndpoint;

        /** OTLP traces 端点。 */
        private String tracesEndpoint;

        /** OTLP logs 端点。 */
        private String logsEndpoint;

        /** OTLP metrics 端点。 */
        private String metricsEndpoint;

        /** collector 出口需要的附加 header（如 Authorization）。 */
        private Map<String, String> headers = new HashMap<>();
    }

    @Data
    public static class Sampling {
        /** 采样率（0.0~1.0，1.0 = 全采样）。 */
        private double probability = 1.0;
    }
}
