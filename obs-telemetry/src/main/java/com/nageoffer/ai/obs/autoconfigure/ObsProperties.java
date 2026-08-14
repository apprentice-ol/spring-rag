package com.nageoffer.ai.obs.autoconfigure;

import com.nageoffer.ai.obs.observation.support.SpanIoLimits;
import com.nageoffer.ai.obs.observation.support.Summarizer;
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

    /** 内容捕获与摘要限额（启动期应用到 SpanIoLimits/Summarizer 全局值）。 */
    private Limits limits = new Limits();

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

    @Data
    public static class Limits {
        /** span/trace 单字段字符上限（防膨胀，含 trace IO 与 raw 输出截断）。默认 20000。 */
        private int maxSpanIo = 20000;

        /** step IO 摘要时单字符串截断长度。默认 200。 */
        private int summarizeMaxString = 200;

        /** 摘要时集合/数组保留的预览条数。默认 3。 */
        private int summarizeMaxPreview = 3;

        /** 摘要时 Map 保留的 entry 数。默认 10。 */
        private int summarizeMaxMapEntries = 10;

        /** 启动期一次性落到 SpanIoLimits/Summarizer 全局值（开任何 span 前调用）。 */
        void apply() {
            SpanIoLimits.configure(maxSpanIo);
            Summarizer.configure(summarizeMaxString, summarizeMaxPreview, summarizeMaxMapEntries);
        }
    }
}
