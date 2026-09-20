package com.jjx.customer.platform.business.orchestration;

import com.jjx.customer.platform.business.task.AgentTaskState;
import com.jjx.customer.platform.common.util.TraceIdExtractor;
import com.jjx.customer.platform.routing.RouteContext;
import com.jjx.customer.platform.routing.RouteDecision;
import com.jjx.customer.platform.routing.RouteRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 规则路由求值器（自 {@link ChatOrchestrator} 决策链第 1 步拆出）：
 * traceId 提取 + 规则表第一次求值（意图未就绪：traceId 类正则规则可命中）。
 */
@Component
@RequiredArgsConstructor
public class RouteEvaluator {

    private final RouteRegistry routeRegistry;

    /** 第一次求值产物：提取到的 traceId（后续 RAG 链 RunContext 也要用）+ 命中的短路决策。 */
    public record FirstPass(String traceId, Optional<RouteDecision> shortCircuit) {
    }

    /**
     * 规则表第一次求值（意图分类前，intent=null：正则短路类规则可命中，
     * 如消息含 traceId 直接进运维诊断——省掉 rewrite+classify 两次 LLM 往返）。
     * 恢复路径不在此短路（traceId 由 ops agent 抽取合并进槽位）。
     */
    public FirstPass evaluateFirstPass(String question, String ruleNormalized,
                                       AgentTaskState activeTask, String agentChoice) {
        String traceId = TraceIdExtractor.extract(question);
        Optional<RouteDecision> shortCircuit = routeRegistry.evaluate(new RouteContext(
                question, ruleNormalized, null, traceId,
                activeTask == null ? null : activeTask.agentId(), agentChoice));
        return new FirstPass(traceId, shortCircuit);
    }
}
