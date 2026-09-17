package com.jjx.customer.platform.agent.framework.route;

import com.jjx.customer.platform.agent.framework.agent.Agent;

import java.util.Map;

/**
 * 路由裁决：选中哪个 Agent（其 Workflow 由绑定解析决定）、需要预填的输入、命中的策略。
 *
 * @param via 命中策略标识（路由 trace step 观测用；可空）
 */
public record RouteDecision(Agent agent, Map<String, Object> prefill, String via) {

    public RouteDecision {
        prefill = prefill == null ? Map.of() : Map.copyOf(prefill);
    }

    public RouteDecision(Agent agent, Map<String, Object> prefill) {
        this(agent, prefill, null);
    }

    public static RouteDecision of(Agent agent) {
        return new RouteDecision(agent, Map.of(), null);
    }

    public RouteDecision withVia(String via) {
        return new RouteDecision(agent, prefill, via);
    }
}
