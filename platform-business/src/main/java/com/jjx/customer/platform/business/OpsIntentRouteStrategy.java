package com.jjx.customer.platform.business;
import com.jjx.customer.platform.business.agents.OpsDiagnoseFrameworkAgent;

import com.jjx.customer.platform.agent.framework.route.RouteContext;
import com.jjx.customer.platform.agent.framework.route.RouteDecision;
import com.jjx.customer.platform.agent.framework.route.RouteStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** 运维域路由策略：意图域命中 ops_diagnose 时选诊断 Agent（优先于知识兜底）。 */
@Component
@RequiredArgsConstructor
public class OpsIntentRouteStrategy implements RouteStrategy {

    private final OpsDiagnoseFrameworkAgent agent;

    @Override
    public int priority() {
        return 50;
    }

    @Override
    public Optional<RouteDecision> match(RouteContext context) {
        return OpsDiagnoseFrameworkAgent.INTENT_DOMAIN.equals(context.intentDomain())
                ? Optional.of(RouteDecision.of(agent)) : Optional.empty();
    }
}
