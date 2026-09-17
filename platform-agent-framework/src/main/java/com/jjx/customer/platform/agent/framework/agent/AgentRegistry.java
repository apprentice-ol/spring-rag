package com.jjx.customer.platform.agent.framework.agent;



import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Agent 注册表（顶层聚合）：id → Agent。重复 id 启动即失败。
 */
public class AgentRegistry {

    private final Map<String, Agent> byId = new LinkedHashMap<>();

    public AgentRegistry(List<Agent> agents) {
        if (agents != null) {
            for (Agent agent : agents) {
                Agent previous = byId.putIfAbsent(agent.id(), agent);
                if (previous != null) {
                    throw new IllegalStateException("重复 Agent id: " + agent.id()
                            + "（" + previous.getClass().getName() + " vs " + agent.getClass().getName() + "）");
                }
            }
        }
    }

    public Optional<Agent> byId(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public List<Agent> all() {
        return List.copyOf(byId.values());
    }
}
