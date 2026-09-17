package com.jjx.customer.platform.routing;


import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 路由规则表（Spring 收集全部 {@link RouteRule}，按 priority 升序求值首个命中）。
 * <p>编排层在两个时机求值：意图分类前（短路类规则）与分类后（意图域规则）——
 * 规则自行判断 RouteContext 输入可用性，两次求值同一张表。
 */
@Slf4j
@Component
public class RouteRegistry {

    private final List<RouteRule> rules;

    public RouteRegistry(List<RouteRule> rules) {
        this.rules = rules.stream()
                .sorted(Comparator.comparingInt(RouteRule::priority))
                .toList();
    }

    /** 按优先级求值，首个命中即返回；全程未命中 = empty（编排层走默认知识检索链）。 */
    public Optional<RouteDecision> evaluate(RouteContext ctx) {
        for (RouteRule rule : rules) {
            Optional<RouteDecision> hit = rule.match(ctx);
            if (hit.isPresent()) {
                log.info("[路由] 命中规则 {} (priority={}) → agent={}",
                        rule.getClass().getSimpleName(), rule.priority(), hit.get().agentType());
                return hit;
            }
        }
        return Optional.empty();
    }

    /** 意图分类 prompt 的动态域清单段（非 null 描述去重拼接；基础域由 prompt 文件自带）。 */
    public List<String> domainDescriptors() {
        return rules.stream()
                .map(RouteRule::domainDescriptor)
                .filter(d -> d != null && !d.isBlank())
                .distinct()
                .toList();
    }
}
