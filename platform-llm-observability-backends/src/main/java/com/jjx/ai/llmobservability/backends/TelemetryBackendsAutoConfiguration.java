package com.jjx.ai.llmobservability.backends;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jjx.ai.llmobservability.backends.langfuse.LangfuseApiClient;
import com.jjx.ai.llmobservability.backends.langfuse.LangfuseAttributeKeyMapper;
import com.jjx.ai.llmobservability.backends.langfuse.LangfuseProperties;
import com.jjx.ai.llmobservability.backends.openobserve.OpenObserveProperties;
import com.jjx.ai.llmobservability.backends.openobserve.OpenObserveQueryClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * llm-observability-backends 的统一自动装配入口。
 *
 * <p>这里负责：① 把后端配置类注册为 Spring Bean（无论 collector 是否开启，业务侧都可注入
 * {@code OpenObserveProperties} / {@code LangfuseProperties}）；② 注册后端属性 key 兼容映射器——
 * 应用侧统一写 OTel 标准通用 key，后端不认标准的地方（目前仅 Langfuse）在本模块应用内追加
 * {@code langfuse.*} 专属 key，<b>不依赖 collector</b>，collector 开与关两种模式下后端收到的字段完全一致
 * （OpenObserve 原生识别 OTel 标准 key，无需映射器）；③ 注册后端<b>读取/编排侧</b>客户端
 * （Langfuse 数据集/评分 API、OpenObserve 日志/span 查询），供应用编排“问题 + 模型回答 vs 黄金答案”
 * 等后端特有动作使用——这些客户端不参与采集热路径。</p>
 */
@AutoConfiguration
@EnableConfigurationProperties({OpenObserveProperties.class, LangfuseProperties.class})
public class TelemetryBackendsAutoConfiguration {

    /**
     * OpenObserve 查询客户端（日志/span 读取）。
     * {@code telemetry.openobserve.query-enabled=false} 停用。
     *
     * @param properties   OpenObserve 连接配置
     * @param objectMapper JSON 解析器
     * @return OpenObserve 查询客户端
     */
    @Bean
    @ConditionalOnProperty(prefix = "telemetry.openobserve", name = "query-enabled",
            havingValue = "true", matchIfMissing = true)
    public OpenObserveQueryClient openObserveQueryClient(OpenObserveProperties properties, ObjectMapper objectMapper) {
        return new OpenObserveQueryClient(properties, objectMapper);
    }

    /**
     * Langfuse 公共 API 客户端（数据集/评分）。
     * {@code telemetry.langfuse.api-enabled=false} 停用；凭据未配置时不注册 Bean，
     * 调用方请用 {@code ObjectProvider} 或 {@code Optional} 注入。
     *
     * @param properties Langfuse 连接配置
     * @return Langfuse API 客户端；凭据缺失时为 null（Spring 视为无 Bean）
     */
    @Bean
    @ConditionalOnProperty(prefix = "telemetry.langfuse", name = "api-enabled",
            havingValue = "true", matchIfMissing = true)
    public LangfuseApiClient langfuseApiClient(LangfuseProperties properties) {
        if (!properties.hasApiCredentials()) {
            return null;
        }
        return new LangfuseApiClient(properties);
    }

    /**
     * Langfuse 属性 key 兼容映射（gen_ai 新标准 → langfuse.observation.*，及 rag.* → langfuse.*）。
     * {@code telemetry.langfuse.attribute-mapping.enabled=false} 停用。
     */
    @Bean
    @ConditionalOnProperty(prefix = "telemetry.langfuse.attribute-mapping", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public LangfuseAttributeKeyMapper langfuseAttributeKeyMapper() {
        return new LangfuseAttributeKeyMapper();
    }
}
