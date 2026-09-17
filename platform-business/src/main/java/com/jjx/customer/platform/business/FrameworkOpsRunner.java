package com.jjx.customer.platform.business;
import com.jjx.customer.platform.business.agents.OpsDiagnoseFrameworkAgent;

import com.jjx.customer.platform.agent.framework.agent.AgentRequest;
import com.jjx.customer.platform.agent.framework.result.ExecutionResult;
import com.jjx.customer.platform.agent.framework.result.OutcomeKind;
import com.jjx.customer.platform.agent.framework.agent.WorkflowEngine;
import com.jjx.customer.platform.business.trace.model.TraceView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运维诊断框架主线执行器（对话链路与 REST 端点共用）。
 *
 * <p>职责收敛为一件：把会话已确认槽位作为执行输入交给框架引擎。会话恢复在编排层
 * （必须先于路由），澄清落库由引擎的会话监听器在出口完成——本类不再重算缺失槽位、
 * 不再自己拼装会话记录（缺失项由框架随结果结构化流出）。</p>
 */
@Component
@RequiredArgsConstructor
public class FrameworkOpsRunner {

    /** 一次诊断的执行产物（交付由调用方决定：SSE / REST 投影）。 */
    public record OpsAnswer(OutcomeKind kind, String text, TraceView trace) {
    }

    private final WorkflowEngine frameworkWorkflowEngine;

    /**
     * @param sessionSlots 会话已确认槽位（可空：单轮调用）
     * @param sessionId    会话标识（非空 = 引擎在出口落会话；REST 单轮为 null）
     */
    public OpsAnswer run(String question, Map<String, String> sessionSlots, String sessionId) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(AgentRequest.ATTR_INTENT_DOMAIN, OpsDiagnoseFrameworkAgent.INTENT_DOMAIN);
        if (sessionSlots != null) {
            attributes.putAll(sessionSlots);
        }
        ExecutionResult result = frameworkWorkflowEngine.execute(new AgentRequest(question, attributes, sessionId));
        return new OpsAnswer(result.kind(), textOf(result), FrameworkTraceMapper.toBusinessTrace(result));
    }

    private static String textOf(ExecutionResult result) {
        return result.text() == null || result.text().isBlank() ? "诊断完成（无结论文本，详见轨迹）。" : result.text();
    }
}
