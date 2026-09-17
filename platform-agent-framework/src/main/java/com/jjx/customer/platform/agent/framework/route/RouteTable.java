package com.jjx.customer.platform.agent.framework.route;

import java.util.Comparator;
import java.util.List;

/**
 * 路由表（引擎内建）：按优先级求值，首个命中即胜出；全程未命中即报错（不静默兜底）。
 */
public class RouteTable {

    private final List<RouteStrategy> strategies;

    public RouteTable(List<RouteStrategy> strategies) {
        this.strategies = (strategies == null ? List.<RouteStrategy>of() : strategies).stream()
                .sorted(Comparator.comparingInt(RouteStrategy::priority))
                .toList();
    }

    /** 求值；未命中抛 {@link IllegalStateException}（含可读提示，便于装配期暴露漏注册）。 */
    public RouteDecision route(RouteContext context) {
        return strategies.stream()
                .map(s -> s.match(context).map(d -> d.withVia(viaOf(s))))
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "路由未命中：input=" + context.request().input()
                                + "，intentDomain=" + context.intentDomain()
                                + "（策略数=" + strategies.size() + "）"));
    }

    /** 策略标识：类简单名（路由 trace step 观测用）。 */
    private static String viaOf(RouteStrategy strategy) {
        return strategy.getClass().getSimpleName();
    }

    /** 已注册策略数（观测/调试用）。 */
    public int size() {
        return strategies.size();
    }
}
