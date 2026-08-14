package com.nageoffer.ai.obs.backends.langfuse;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Langfuse 连接配置（前缀 {@code obs.langfuse}）。
 *
 * <p>Langfuse 是 LLM / trace 可视化后端。collector 开启时由 otel-collector 转发；
 * collector 关闭时应用通过 {@code otlpTracesEndpoint} 直连。</p>
 */
@Data
@ConfigurationProperties("obs.langfuse")
public class LangfuseProperties {

    /** Langfuse Web UI 地址。 */
    private String url = "http://localhost:3000";

    /** OTLP traces 入口。collector 关闭时，应用直连该端点。 */
    private String otlpTracesEndpoint;

    /** OTLP 鉴权 public key（pk-lf-...）。 */
    private String publicKey;

    /** OTLP 鉴权 secret key（sk-lf-...）。 */
    private String secretKey;
}
