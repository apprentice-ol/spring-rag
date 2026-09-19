package com.jjx.customer.platform.business.routing;
import com.jjx.customer.platform.business.engine.AgentCatalog;


import com.jjx.customer.platform.business.ops.OpsSlotCatalog;
import com.jjx.customer.platform.routing.RouteContext;
import com.jjx.customer.platform.routing.RouteDecision;
import com.jjx.customer.platform.routing.RouteRule;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Optional;

/**
 * traceId 短路规则：消息含 traceId（正则提取，零 LLM 成本）直接进运维诊断并预填槽位；
 * 优先级最高——省掉改写 + 意图分类两次 LLM 往返。
 */
@Component
public class TraceIdShortCircuitRule implements RouteRule {

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public Optional<RouteDecision> match(RouteContext ctx) {
        if (!StringUtils.hasText(ctx.extractedTraceId())) {
            return Optional.empty();
        }
        return Optional.of(new RouteDecision(AgentCatalog.OPS.id(),
                OpsSlotCatalog.sanitized(Map.of(OpsSlotCatalog.TRACE_ID, ctx.extractedTraceId()))));
    }
}
