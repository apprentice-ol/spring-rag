package com.nageoffer.ai.obs.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * obs 可观测组件的统一配置（前缀 {@code obs}）。
 *
 * <p>应用只配这一个 {@code obs.*} 块即可管理全部可观测性，分 3 层：</p>
 * <ul>
 *   <li>{@link #tracerName} / {@link #llm} —— 埋点核心（obs 组件本身）。</li>
 *   <li>{@link #export} —— trace 发送（OTLP endpoint + 采样率）；由
 *       {@link ObsExportEnvironmentPostProcessor} 桥接到 Spring Boot actuator 的 OTel exporter
 *       （{@code management.otlp.tracing.endpoint} / {@code management.tracing.sampling.probability}），
 *       应用无需直接配 management。</li>
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

    /** LLM 埋点。 */
    private Llm llm = new Llm();

    /** trace 发送（桥接到 Spring Boot actuator）。 */
    private Export export = new Export();

    @Data
    public static class Llm {
        /** LLM 埋点总开关：prompt/completion/model/usage → OTel gen_ai.*。 */
        private boolean contentAttributes = true;
    }

    @Data
    public static class Export {
        private Otlp otlp = new Otlp();
        private Sampling sampling = new Sampling();

        @Data
        public static class Otlp {
            /** OTLP endpoint（→ otel-collector，collector 再扇出 OpenObserve + Langfuse）。 */
            private String endpoint;
        }

        @Data
        public static class Sampling {
            /** 采样率（0.0~1.0，1.0 = 全采样）。 */
            private double probability = 1.0;
        }
    }
}
