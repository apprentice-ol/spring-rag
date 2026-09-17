package com.jjx.customer.platform.routing;


import java.util.Map;

/**
 * 路由裁决：目标执行者 + 预填槽位（如消息里正则提取到的 trace_id，随目标 agent 的槽位目录合并）。
 */
public record RouteDecision(String agentType, Map<String, String> prefillSlots) {

    public static RouteDecision to(String agentType) {
        return new RouteDecision(agentType, Map.of());
    }
}
