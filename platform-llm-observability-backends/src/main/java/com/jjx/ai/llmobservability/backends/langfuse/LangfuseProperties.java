package com.jjx.ai.llmobservability.backends.langfuse;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Langfuse 连接配置（前缀 {@code telemetry.langfuse}）。
 *
 * <p>Langfuse 是 LLM / trace 可视化后端。collector 开启时由 otel-collector 转发；
 * collector 关闭时应用通过 {@code otlpTracesEndpoint} 直连。</p>
 */
@Data
@ConfigurationProperties("telemetry.langfuse")
public class LangfuseProperties {

    /** Langfuse Web UI 地址。 */
    private String url = "http://localhost:3000";

    /** OTLP traces 入口。collector 关闭时，应用直连该端点。 */
    private String otlpTracesEndpoint;

    /** OTLP 鉴权 public key（pk-lf-...）。 */
    private String publicKey;

    /** OTLP 鉴权 secret key（sk-lf-...）。 */
    private String secretKey;

    /** API Basic Auth（base64(pk:sk)）。配置了 {@code LANGFUSE_AUTH} 时优先使用，否则由 public/secret key 拼接。 */
    private String auth;

    /** 是否启用数据集/评分 API 客户端（读取与编排侧，不影响 OTLP 采集）。 */
    private boolean apiEnabled = true;

    /** 应用侧 rag.* → langfuse.* 属性映射（collector 无关，两种模式行为一致）。 */
    private final AttributeMapping attributeMapping = new AttributeMapping();

    /**
     * 是否具备调用 Langfuse 公共 API 的凭据。
     *
     * @return 已配置 {@code auth}，或同时配置 {@code publicKey} 与 {@code secretKey} 时为 true
     */
    public boolean hasApiCredentials() {
        if (hasText(auth)) {
            return true;
        }
        return hasText(publicKey) && hasText(secretKey);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @Data
    public static class AttributeMapping {
        /**
         * 是否启用属性 key 映射（SpanAttributeExporter / BaggageAttributeSpanProcessor 追加写 langfuse.* key）。
         * 关闭后 Langfuse 只收到 gen_ai.* 原生字段，不再展示会话分组/trace tags/TTFT 等。
         */
        private boolean enabled = true;
    }
}
