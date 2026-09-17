package com.jjx.customer.platform.agent.framework.route;

import java.util.Optional;

/**
 * 路由策略 SPI（对外注册开放）：自定义匹配（意图域 / 正则 / 用户分组 / AB 分流）。
 *
 * <p>约束：引擎负责求值顺序（priority 升序）与命中即止；策略只回答"是否命中、命中谁"。</p>
 */
public interface RouteStrategy {

    /** 优先级（数值小者先求值）。 */
    int priority();

    /** 求值：命中返回裁决，不命中返回 empty。 */
    Optional<RouteDecision> match(RouteContext context);
}
