package com.agentframework.infra.modelgateway;

import com.agentframework.crosscutting.metrics.Metrics;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 默认模型网关：按 {@code provider} 标识路由到注册的提供方。
 *
 * <p>未命中显式提供方时按默认提供方、唯一提供方依次回退；全部不可用时抛出明确异常，避免静默失败。</p>
 */
public final class DefaultModelGateway implements ModelGateway {

    private final Map<String, ModelProvider> providers = new LinkedHashMap<>();
    private final String defaultProviderId;
    private final Metrics metrics;

    /**
     * @param defaultProviderId 默认提供方标识，可为 null
     * @param metrics           指标采集器，可为 null
     */
    public DefaultModelGateway(String defaultProviderId, Metrics metrics) {
        this.defaultProviderId = defaultProviderId;
        this.metrics = metrics;
    }

    /**
     * 注册提供方。
     *
     * @param provider 提供方实现
     * @return 当前网关
     */
    public DefaultModelGateway register(ModelProvider provider) {
        if (provider != null && provider.id() != null) {
            providers.put(provider.id(), provider);
        }
        return this;
    }

    @Override
    public ModelResponse complete(ModelRequest request, ModelCallContext context) {
        ModelProvider selected = resolve(request.provider());
        long start = System.nanoTime();
        try {
            ModelResponse response = selected.complete(request, context);
            if (metrics != null) {
                metrics.counter("model.calls", 1L, Map.of("provider", selected.id(), "model", request.model()));
                metrics.counter("model.tokens", response.usage().total(), Map.of("provider", selected.id()));
            }
            return response.withIdentity(selected.id(), request.model());
        } catch (RuntimeException e) {
            if (metrics != null) {
                metrics.counter("model.errors", 1L, Map.of("provider", selected.id()));
            }
            throw e;
        } finally {
            if (metrics != null) {
                metrics.histogram("model.duration_ms", (System.nanoTime() - start) / 1_000_000.0,
                        Map.of("provider", selected.id()));
            }
        }
    }

    @Override
    public ModelResponse stream(ModelRequest request, ModelCallContext context, StreamHandler handler) {
        ModelProvider selected = resolve(request.provider());
        long start = System.nanoTime();
        try {
            ModelResponse response = selected.stream(request, context, handler);
            if (metrics != null) {
                metrics.counter("model.calls", 1L, Map.of("provider", selected.id(), "model", request.model()));
                metrics.counter("model.tokens", response.usage().total(), Map.of("provider", selected.id()));
            }
            return response.withIdentity(selected.id(), request.model());
        } catch (RuntimeException e) {
            if (metrics != null) {
                metrics.counter("model.errors", 1L, Map.of("provider", selected.id()));
            }
            throw e;
        } finally {
            if (metrics != null) {
                metrics.histogram("model.duration_ms", (System.nanoTime() - start) / 1_000_000.0,
                        Map.of("provider", selected.id()));
            }
        }
    }

    private ModelProvider resolve(String providerId) {
        ModelProvider provider = providers.get(providerId);
        if (provider == null && defaultProviderId != null) {
            provider = providers.get(defaultProviderId);
        }
        if (provider == null && providers.size() == 1) {
            provider = providers.values().iterator().next();
        }
        if (provider == null) {
            throw new IllegalStateException("未找到模型提供方：" + providerId
                    + "，已注册：" + providers.keySet());
        }
        return provider;
    }

    /** @return 已注册的提供方标识 */
    public Set<String> providerIds() {
        return Set.copyOf(providers.keySet());
    }
}
