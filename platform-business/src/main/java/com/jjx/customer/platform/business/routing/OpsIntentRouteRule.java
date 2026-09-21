package com.jjx.customer.platform.business.routing;
import com.jjx.customer.platform.business.engine.AgentCatalog;


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

    public static final String DOMAIN = AgentCatalog.OPS.intentDomain();

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
        if (AgentCatalog.KNOWLEDGE.id().equals(ctx.agentChoice())) {
            return Optional.empty();
        }
        if (ctx.intent().getConfidence() < chatProperties.getDiagnoseMinConfidence()) {
            return Optional.empty();
        }
        return Optional.of(RouteDecision.to(AgentCatalog.OPS.id()));
    }

    @Override
    public String domainDescriptor() {
        return DOMAIN + "：排查**线上系统故障**。必须同时满足两点才选此域："
                + "① 明确的排障诉求（帮我排查/查一下日志/定位一下问题）；"
                + "② 可定位的线上要素：traceId、订单号/流水号、接口路径、或环境+具体接口。"
                + "只是询问概念/原理/报错含义/怎么解决（\"xxx 报错怎么办\"\"Refresh-Token\"这类求知识的）"
                + "一律选 knowledge，即使句中出现\"报错/失败\"字眼"
                + "（例：✓ \"52462c684e1c47242ffea1bc96af94c3 是什么错\"、"
                + "✓ \"订单 ORD53f64352 下单失败，帮我查日志\"；"
                + "✗ \"token 获取报错怎么解决\"、✗ \"Refresh-Token\"）";
    }
}
