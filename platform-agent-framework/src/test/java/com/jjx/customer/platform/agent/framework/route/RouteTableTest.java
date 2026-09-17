package com.jjx.customer.platform.agent.framework.route;

import com.jjx.customer.platform.agent.framework.agent.Agent;
import com.jjx.customer.platform.agent.framework.agent.AgentRequest;
import com.jjx.customer.platform.agent.framework.capability.AgentCapability;
import com.jjx.customer.platform.agent.framework.workflow.Workflow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RouteTableTest {

    /** 极简测试 Agent（只用于路由断言）。 */
    static Agent agent(String id, String domain) {
        return new Agent() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String intentDomain() {
                return domain;
            }

            @Override
            public Set<AgentCapability> capabilities() {
                return Set.of();
            }

            @Override
            public Workflow workflow() {
                throw new UnsupportedOperationException("路由测试不涉及流程");
            }
        };
    }

    private static RouteStrategy strategy(int priority, String domain) {
        return new RouteStrategy() {
            @Override
            public int priority() {
                return priority;
            }

            @Override
            public Optional<RouteDecision> match(RouteContext context) {
                return domain.equals(context.intentDomain())
                        ? Optional.of(RouteDecision.of(agent(domain, domain)))
                        : Optional.empty();
            }
        };
    }

    @Test
    void 按优先级求值_首个命中胜出() {
        RouteTable table = new RouteTable(List.of(strategy(50, "ops"), strategy(10, "knowledge")));
        RouteContext context = new RouteContext(AgentRequest.of("问题"), "knowledge");

        assertEquals("knowledge", table.route(context).agent().id());
    }

    @Test
    void 未命中_报错而不是静默兜底() {
        RouteTable table = new RouteTable(List.of(strategy(10, "ops")));

        assertThrows(IllegalStateException.class,
                () -> table.route(new RouteContext(AgentRequest.of("问题"), "unknown")));
    }

    @Test
    void 空表_报错() {
        assertThrows(IllegalStateException.class,
                () -> new RouteTable(List.of()).route(RouteContext.preIntent(AgentRequest.of("问题"))));
    }
}
