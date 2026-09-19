package com.agentframework.definition.agent;

import com.agentframework.definition.policy.RetryPolicy;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型配置：Agent 或节点的模型绑定信息。
 *
 * <p>框架不直接对接厂商 SDK，{@code provider} 只是模型网关中的逻辑标识，
 * 更换厂商因此属于配置变更，不需要改代码。</p>
 *
 * @param provider    模型提供方标识
 * @param model       模型名
 * @param temperature 采样温度，{@link #UNSET_TEMPERATURE} 表示未设置
 * @param maxTokens   最大生成 token 数，0 表示未设置
 * @param timeout     单次调用超时
 * @param retry       重试策略
 * @param parameters  透传给厂商的扩展参数
 */
public record ModelConfig(
        String provider,
        String model,
        double temperature,
        int maxTokens,
        Duration timeout,
        RetryPolicy retry,
        Map<String, Object> parameters) {

    public static final double UNSET_TEMPERATURE = -1.0;

    public ModelConfig {
        provider = provider == null || provider.isBlank() ? "default" : provider;
        model = model == null || model.isBlank() ? "default" : model;
        timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
        retry = retry == null ? RetryPolicy.none() : retry;
        parameters = parameters == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
    }

    /** @return 全部取缺省值的模型配置 */
    public static ModelConfig defaults() {
        return new ModelConfig(null, null, UNSET_TEMPERATURE, 0, null, null, null);
    }

    /**
     * @param provider 模型提供方标识
     * @param model    模型名
     * @return 仅指定提供方与模型名的配置
     */
    public static ModelConfig of(String provider, String model) {
        return new ModelConfig(provider, model, UNSET_TEMPERATURE, 0, null, null, null);
    }

    /** @return 采样温度是否已显式设置 */
    public boolean temperatureSet() {
        return temperature >= 0;
    }

    /**
     * @param temperature 采样温度
     * @return 覆盖温度后的配置
     */
    public ModelConfig withTemperature(double temperature) {
        return new ModelConfig(provider, model, temperature, maxTokens, timeout, retry, parameters);
    }

    /**
     * @param maxTokens 最大生成 token 数
     * @return 覆盖 token 上限后的配置
     */
    public ModelConfig withMaxTokens(int maxTokens) {
        return new ModelConfig(provider, model, temperature, maxTokens, timeout, retry, parameters);
    }

    /**
     * @param timeout 单次调用超时
     * @return 覆盖超时后的配置
     */
    public ModelConfig withTimeout(Duration timeout) {
        return new ModelConfig(provider, model, temperature, maxTokens, timeout, retry, parameters);
    }

    /**
     * @param retry 重试策略
     * @return 覆盖重试策略后的配置
     */
    public ModelConfig withRetry(RetryPolicy retry) {
        return new ModelConfig(provider, model, temperature, maxTokens, timeout, retry, parameters);
    }

    /**
     * @param key   扩展参数名
     * @param value 扩展参数值
     * @return 追加扩展参数后的配置
     */
    public ModelConfig withParameter(String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(parameters);
        merged.put(key, value);
        return new ModelConfig(provider, model, temperature, maxTokens, timeout, retry, merged);
    }

    /**
     * 与上层配置合并：{@code override} 中显式设置的值优先，其余沿用当前配置。
     *
     * @param override 上层（例如节点级）模型配置，可为 null
     * @return 合并后的模型配置
     */
    public ModelConfig overlay(ModelConfig override) {
        if (override == null) {
            return this;
        }
        Map<String, Object> merged = new LinkedHashMap<>(parameters);
        merged.putAll(override.parameters);
        return new ModelConfig(
                override.provider,
                override.model,
                override.temperatureSet() ? override.temperature : temperature,
                override.maxTokens > 0 ? override.maxTokens : maxTokens,
                override.timeout,
                override.retry.enabled() ? override.retry : retry,
                merged);
    }
}
