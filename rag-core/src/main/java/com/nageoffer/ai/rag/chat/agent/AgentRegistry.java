package com.nageoffer.ai.rag.chat.agent;

import com.nageoffer.ai.rag.config.properties.AgentProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Agent 范式注册中心（仿 {@code ChunkingStrategyFactory}）。
 * <p>启动时收集所有 {@link RagAgent} bean，按 {@link RagAgent#getType()} 建 {@code code→agent} 映射；
 * 重复 type 抛异常（启动失败）。运行时按 code 取范式，未注册抛 {@link IllegalArgumentException}。
 * <p>加新范式 = 新增一个 {@code RagAgent} 实现，零改动本类与现有代码（开闭原则）。
 */
@Component
@RequiredArgsConstructor
public class AgentRegistry {

    private final List<RagAgent> agents;
    private final AgentProperties agentProperties;

    private volatile Map<String, RagAgent> registry = Map.of();

    @PostConstruct
    void init() {
        Map<String, RagAgent> map = new HashMap<>();
        for (RagAgent a : agents) {
            RagAgent old = map.put(a.getType(), a);
            if (old != null) {
                throw new IllegalStateException(
                        "Duplicate RagAgent for type: " + a.getType()
                                + " (" + old.getClass().getName() + " vs " + a.getClass().getName() + ")");
            }
        }
        this.registry = Map.copyOf(map);
    }

    public Optional<RagAgent> find(String type) {
        if (type == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(registry.get(type));
    }

    /** 按 type 取范式，未注册抛异常（异常信息含全部可用 type 便于排查）。 */
    public RagAgent require(String type) {
        Objects.requireNonNull(type, "agent type must not be null");
        return find(type).orElseThrow(() -> new IllegalArgumentException(
                "Unknown agent type: " + type + ", available=" + registry.keySet()));
    }

    /** 全局默认范式对应的 agent（由 AgentProperties.paradigm 决定）。 */
    public RagAgent defaultAgent() {
        return require(agentProperties.paradigmEnum().getCode());
    }

    /** 所有已注册范式标识（前端对照面板列出可选项用）。 */
    public Set<String> availableTypes() {
        return registry.keySet();
    }
}
