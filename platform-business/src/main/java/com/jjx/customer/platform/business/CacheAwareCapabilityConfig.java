package com.jjx.customer.platform.business;

import com.jjx.customer.platform.agent.framework.agent.Agent;
import com.jjx.customer.platform.agent.framework.capability.AgentCapability;
import com.jjx.customer.platform.agent.framework.capability.CapabilityConfig;
import com.jjx.customer.platform.cache.CacheProperties;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 能力开关配置（能力三方模型的 Config 侧）：缓存层关闭时收窄对应能力位。
 *
 * <p>按「声明集 − 全局禁用集」返回——恒为声明的子集（配置只能收窄，D8/不变量 6），
 * 不需要按 agentId 硬编码。eval 或运维经 {@code rag.cache.*} 关缓存即同时收窄
 * ANSWER_CACHE / SEMANTIC_CACHE，三方模型真正驱动管线行为。</p>
 */
@Component
public class CacheAwareCapabilityConfig implements CapabilityConfig {

    private final CacheProperties cacheProperties;

    public CacheAwareCapabilityConfig(CacheProperties cacheProperties) {
        this.cacheProperties = cacheProperties;
    }

    @Override
    public Optional<Set<AgentCapability>> enabledFor(Agent agent) {
        Set<AgentCapability> disabled = disabledCapabilities();
        if (disabled.isEmpty()) {
            return Optional.empty();
        }
        Set<AgentCapability> enabled = new HashSet<>(agent.capabilities());
        enabled.removeAll(disabled);
        // EnumSet.copyOf 不接受空集合（IllegalArgumentException: Collection is empty）：
        // 能力位被收窄到空、或 agent 本身未声明能力（如 ops_diagnose）时必须提前返回空集。
        if (enabled.isEmpty()) {
            return Optional.of(Set.of());
        }
        return Optional.of(Set.copyOf(EnumSet.copyOf(enabled)));
    }

    private Set<AgentCapability> disabledCapabilities() {
        Set<AgentCapability> disabled = EnumSet.noneOf(AgentCapability.class);
        if (!cacheProperties.isEnabled()) {
            disabled.add(AgentCapability.ANSWER_CACHE);
            disabled.add(AgentCapability.SEMANTIC_CACHE);
            return disabled;
        }
        if (!cacheProperties.getAnswer().isEnabled()) {
            disabled.add(AgentCapability.ANSWER_CACHE);
        }
        if (!cacheProperties.getSemantic().isEnabled()) {
            disabled.add(AgentCapability.SEMANTIC_CACHE);
        }
        return disabled;
    }
}
