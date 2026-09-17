package com.jjx.customer.platform.business.routing;
import com.jjx.customer.platform.business.agents.ReactFrameworkAgent;

import com.jjx.customer.platform.agent.framework.route.RouteContext;
import com.jjx.customer.platform.agent.framework.route.RouteDecision;
import com.jjx.customer.platform.agent.framework.route.RouteStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** react_loop 轴路由：显式要求工具循环范式时选它（优先于知识兜底，低于 ops 域）。 */
@Component
@RequiredArgsConstructor
public class ReactIntentRouteStrategy implements RouteStrategy {

    private final ReactFrameworkAgent agent;

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public Optional<RouteDecision> match(RouteContext context) {
        return ReactFrameworkAgent.INTENT_DOMAIN.equals(context.intentDomain())
                ? Optional.of(RouteDecision.of(agent)) : Optional.empty();
    }
}
