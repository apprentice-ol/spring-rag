package com.jjx.customer.platform.business.routing;
import com.jjx.customer.platform.business.agents.KnowledgeFrameworkAgent;
import com.jjx.customer.platform.business.agents.OpsDiagnoseFrameworkAgent;

import com.jjx.customer.platform.routing.RouteContext;
import com.jjx.customer.platform.routing.RouteDecision;
import com.jjx.customer.platform.routing.RouteRule;
import com.jjx.customer.platform.config.properties.ChatProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 运维域意图规则：意图分类命中 ops_diagnose 且置信度达门槛才进诊断（低置信度不劫持）；
 * 用户显式选择 knowledge 时压制（库里可能就有报错说明文档）。
 *
 * <p>域描述随规则注册，自动进入意图分类 prompt 的动态域清单。</p>
 */
@Component
@RequiredArgsConstructor
public class OpsIntentRouteRule implements RouteRule {

    public static final String DOMAIN = OpsDiagnoseFrameworkAgent.INTENT_DOMAIN;

    private final ChatProperties chatProperties;

    @Override
    public int priority() {
        return 50;
    }

    @Override
    public Optional<RouteDecision> match(RouteContext ctx) {
        if (ctx.intent() == null || !DOMAIN.equals(ctx.intent().getDomain())) {
            return Optional.empty();
        }
        if (KnowledgeFrameworkAgent.ID.equals(ctx.agentChoice())) {
            return Optional.empty();
        }
        if (ctx.intent().getConfidence() < chatProperties.getDiagnoseMinConfidence()) {
            return Optional.empty();
        }
        return Optional.of(RouteDecision.to(OpsDiagnoseFrameworkAgent.ID));
    }

    @Override
    public String domainDescriptor() {
        return DOMAIN + "：排查线上报错/异常/日志/接口故障，用户给出报错信息、traceId、"
                + "或要求「诊断/查日志/为什么失败」时选此域"
                + "（例：✓ \"帮我看看刚才的报错\"、\"52462c684e1c47242ffea1bc96af94c3 是什么错\"）";
    }
}
