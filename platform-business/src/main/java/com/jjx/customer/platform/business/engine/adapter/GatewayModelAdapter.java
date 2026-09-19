package com.jjx.customer.platform.business.engine.adapter;

import com.agentframework.infra.modelgateway.ChatMessage;
import com.agentframework.infra.modelgateway.DefaultModelGateway;
import com.agentframework.infra.modelgateway.ModelCallContext;
import com.agentframework.infra.modelgateway.ModelRequest;
import com.jjx.customer.platform.business.ops.slot.OpsSlotExtractor;
import com.jjx.customer.platform.config.llm.SpringAiModelProvider;
import java.time.Duration;
import java.util.List;

/**
 * 走新内核模型网关的单轮问答适配器（server 侧 {@code GatewayAskClient} 的等价物）：
 * 给节点外部的轻量 LLM 调用（ops 抽槽 / 自主补全推断 / replan 裁决）统一补上
 * 网关横切（指标、超时、重试、trace）。
 *
 * <p>实现 {@link OpsSlotExtractor.Model} 最小接口，装配进 {@code SharedDeps.model}；
 * 与引擎 LLM 节点共享同一个网关与 provider（DeepSeek 端点同源）。</p>
 */
public class GatewayModelAdapter implements OpsSlotExtractor.Model {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final DefaultModelGateway gateway;

    /**
     * @param gateway 共享模型网关（已注册 spring-ai provider）
     */
    public GatewayModelAdapter(DefaultModelGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public String ask(String system, String user) {
        ModelRequest request = new ModelRequest(SpringAiModelProvider.PROVIDER_ID, "chat",
                List.of(ChatMessage.system(system == null ? "" : system),
                        ChatMessage.user(user == null ? "" : user)),
                null, null, null, java.util.Map.of());
        return gateway.complete(request,
                new ModelCallContext("app", "agent-sidecall", null, TIMEOUT, null)).content();
    }
}
