package com.jjx.customer.platform.business.routing;
import com.jjx.customer.platform.business.agents.KnowledgeFrameworkAgent;

import com.jjx.customer.platform.agent.framework.route.RouteContext;
import com.jjx.customer.platform.agent.framework.route.RouteDecision;
import com.jjx.customer.platform.agent.framework.route.RouteStrategy;
import com.jjx.customer.platform.intent.IntentResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 知识域路由策略：意图域为 knowledge（或未分类）时落知识问答 Agent。
 *
 * <p>优先级放到最后（兜底）：其他域的规则（如运维诊断）先求值，未命中才走知识检索——
 * 与"识别不到 = 知识检索"的既有口径一致。</p>
 */
@Component
@RequiredArgsConstructor
public class KnowledgeRouteStrategy implements RouteStrategy {

    private final KnowledgeFrameworkAgent agent;

    @Override
    public int priority() {
        return 1000;
    }

    @Override
    public Optional<RouteDecision> match(RouteContext context) {
        String domain = context.intentDomain();
        if (domain == null || IntentResult.DOMAIN_KNOWLEDGE.equals(domain)) {
            return Optional.of(RouteDecision.of(agent));
        }
        return Optional.empty();
    }
}
