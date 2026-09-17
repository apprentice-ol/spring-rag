package com.jjx.customer.platform.agent.framework.agent;

import java.util.Map;

/**
 * 一次执行请求。
 *
 * @param input      用户输入（原始问题/指令）
 * @param attributes 附加属性（调用方自定义，框架不解释语义）
 * @param sessionId  会话标识（null = 无会话：引擎不读不写会话状态）
 */
public record AgentRequest(String input, Map<String, Object> attributes, String sessionId) {

    /** 属性键：意图域（编排层分类后传入，引擎据此求值路由规则）。 */
    public static final String ATTR_INTENT_DOMAIN = "intentDomain";

    public AgentRequest {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        sessionId = sessionId == null || sessionId.isBlank() ? null : sessionId;
    }

    public AgentRequest(String input, Map<String, Object> attributes) {
        this(input, attributes, null);
    }

    public static AgentRequest of(String input) {
        return new AgentRequest(input, Map.of(), null);
    }

    /** 挂上会话标识（引擎据此在执行出口落会话）。 */
    public AgentRequest withSession(String sessionId) {
        return new AgentRequest(input, attributes, sessionId);
    }

    public Object attribute(String key) {
        return attributes.get(key);
    }
}
