package com.nageoffer.ai.obs.backends.openobserve;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenObserve 连接配置（前缀 {@code obs.openobserve}）。
 *
 * <p>OpenObserve 是 obs-telemetry 的一个具体后端，不属于通用核心。本类由
 * {@code obs-telemetry-backends} 模块持有，供业务查询和直连 exporter 使用。</p>
 */
@Data
@ConfigurationProperties("obs.openobserve")
public class OpenObserveProperties {

    /** API 根地址（到组织），默认本地 default 组织。 */
    private String url = "http://localhost:5080/api/default";

    /** OTLP 基础地址。collector 关闭时，直连 OpenObserve OTLP 日志/指标入口使用。 */
    private String otlpBaseUrl;

    /** OTLP traces 入口。collector 关闭时可选覆盖。 */
    private String otlpTracesEndpoint;

    /** OTLP logs 入口。collector 关闭时可选覆盖。 */
    private String otlpLogsEndpoint;

    /** OTLP metrics 入口。collector 关闭时可选覆盖。 */
    private String otlpMetricsEndpoint;

    /** Web UI 根地址（浏览器可达，前端链路追踪页跳转用）。 */
    private String webUrl = "http://localhost:5080";

    /** 日志 stream 名（OpenObserve 实际存储名，下划线）。 */
    private String stream = "springai_rag_logs";

    /** Basic Auth 用户名。 */
    private String username = "admin@openobserve.io";

    /** Basic Auth 密码。 */
    private String password = "OpenObserve@2026";

    /** 单次查询日志上限。 */
    private int maxLogs = 200;

    /** 查询时间窗口（天）。 */
    private int lookbackDays = 7;
}
