package com.jjx.ai.llmobservability.autoconfigure;

import com.jjx.ai.llmobservability.observation.support.SpanIoLimits;
import com.jjx.ai.llmobservability.observation.support.Summarizer;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * telemetry 可观测组件的统一配置（前缀 {@code telemetry}）。
 *
 * <p>应用只配这一个 {@code telemetry.*} 块即可管理全部可观测性：</p>
 * <ul>
 *   <li>{@link #tracerName} —— 埋点核心（telemetry 组件本身）。</li>
 *   <li>{@link #collector} —— OTLP Collector 连接；由
 *       {@link TelemetryEnvironmentPostProcessor} 桥接到 Spring Boot actuator 的 OTel exporter
 *       （{@code management.otlp.tracing.endpoint}）。</li>
 *   <li>{@link #sampling} —— trace 采样率，桥接到 {@code management.tracing.sampling.probability}。</li>
 * </ul>
 *
 * <p>{@code telemetry.openobserve.*} 由业务侧（rag-core 的 OpenObserveProperties）绑定，本类不含——openobserve 是
 * RAG 业务的日志诊断连接，不属公共组件。</p>
 */
@Data
@ConfigurationProperties("telemetry")
public class TelemetryProperties {

    /** OTel tracer 名（openTrace 开无父新 trace 根 span 用），区分服务。 */
    private String tracerName = "telemetry";

    /**
     * 无 OTel 标准的框架属性（trace.tags / trace.metadata.* / release / first_token_at / step）的命名空间前缀。
     * 未配置时取 {@code spring.application.name}，再缺省 {@code telemetry}（启动期一次性生效，见 AttributeKeys）。
     */
    private String attributeNamespace;

    /** OTLP Collector 连接（应用唯一的 OTLP 出口）。 */
    private Collector collector = new Collector();

    /** trace 采样。 */
    private Sampling sampling = new Sampling();

    /** 传播与 span 属性增强。 */
    private Propagation propagation = new Propagation();

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
    public static class Propagation {
        /**
         * 是否把 OTel Baggage 条目自动落为 trace 内所有 span 的属性
         * （{@code BaggageAttributeSpanProcessor}，会话/用户等 trace 级聚合字段依赖它）。
         */
        private boolean baggageSpanAttributes = true;
    }

    @Data
    public static class Limits {
        /** span/trace 单字段字符上限（防膨胀，含 trace IO 与 raw 输出截断）。默认 20000。 */
        private int maxSpanIo = 20000;

        /** 是否启用输入/输出摘要。测试阶段可关闭以保留完整 JSON。默认 true。 */
        private boolean summarize = true;

        /** 是否启用单字段字符截断。测试阶段可关闭以保留完整输入/输出。默认 true。 */
        private boolean truncate = true;

        /** step IO 摘要时单字符串截断长度。默认 200。 */
        private int summarizeMaxString = 200;

        /** 摘要时集合/数组保留的预览条数。默认 3。 */
        private int summarizeMaxPreview = 3;

        /** 摘要时 Map 保留的 entry 数。默认 10。 */
        private int summarizeMaxMapEntries = 10;

        /** 启动期一次性落到 SpanIoLimits/Summarizer 全局值（开任何 span 前调用）。 */
        void apply() {
            SpanIoLimits.configure(maxSpanIo, truncate);
            Summarizer.configure(summarizeMaxString, summarizeMaxPreview, summarizeMaxMapEntries, summarize);
        }
    }
}
