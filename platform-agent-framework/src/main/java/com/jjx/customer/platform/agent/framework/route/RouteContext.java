package com.jjx.customer.platform.agent.framework.route;

import com.jjx.customer.platform.agent.framework.agent.AgentRequest;

/**
 * 路由求值上下文。
 *
 * <p>引擎分两轮求值：预处理短路类（{@code intentDomain == null}）与意图域类（意图就绪后）；
 * 规则自行判断输入可用性。</p>
 *
 * @param request     原始请求
 * @param intentDomain 意图域（第一轮为 null）
 */
public record RouteContext(AgentRequest request, String intentDomain) {

    public static RouteContext preIntent(AgentRequest request) {
        return new RouteContext(request, null);
    }
}
