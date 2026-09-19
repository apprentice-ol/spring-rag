package com.jjx.customer.platform.business.orchestration;

import com.jjx.ai.llmobservability.observation.logging.TelemetryLogger;
import com.jjx.customer.platform.business.ops.OpsRunner;
import com.jjx.customer.platform.business.ops.slot.OpsSlotCatalog;
import com.jjx.customer.platform.business.runtime.DegradeGuard;
import com.jjx.customer.platform.business.trace.model.TraceView;
import com.jjx.customer.platform.delivery.DeliveryPortFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * agent 直执行分支分派器（自 {@link ChatOrchestrator} 拆出）：
 * workflow 型 agent 统一入口（构建 {@code AgentTask}（含恢复会话与预填槽位）→ 执行 → Outcome 分派；
 * REST 诊断入口为 DiagnoseController 薄壳，同一 agent 单一实现）。
 * 目标 agent 由调用方解析（traceId 短路 / 会话恢复 / 显式选择 / 意图路由各自按语义路由）。
 */
@Component
@RequiredArgsConstructor
public class AgentBranchDispatcher<S> {

    private static final TelemetryLogger log = TelemetryLogger.of(AgentBranchDispatcher.class);

    private final OpsRunner opsRunner;
    private final DeliveryPortFactory<S> deliveryPortFactory;
    private final DegradeGate<S> degradeGate;

    /**
     * 执行 agent 分支并按 Outcome 分派交付（追问 / 直答 / 升级 / 带上下文直答）。
     *
     * @param agentType    目标 agent 类型（当前为 ops_diagnose）
     * @param prefillSlots 正则预填槽位（如消息里提取到的 traceId），可空
     * @param resumedSlots 恢复的会话已确认槽位（追问补充轮），可空
     */
    public void runAgentBranch(String agentType, String question, String conversationId, S sink,
                               String otelTraceId,
                               Map<String, String> prefillSlots,
                               Map<String, String> resumedSlots) {
        // 槽位合并：会话已确认值 + 本轮预填（trace_id 等正则提取结果；只保留 ops 目录内槽位）
        Map<String, String> mergedSlots = new LinkedHashMap<>();
        if (resumedSlots != null) {
            mergedSlots.putAll(resumedSlots);
        }
        mergedSlots.putAll(OpsSlotCatalog.sanitized(prefillSlots));

        // 降级闸：Redis 断路器 OPEN 期间收紧 ops 诊断并发（缓存保护消失时给下游留活口）
        DegradeGuard.Lease opsLease = degradeGate.tryAcquire(conversationId, sink, otelTraceId);
        if (opsLease == null) {
            return;
        }

        deliveryPortFactory.begin(sink, conversationId, question, otelTraceId, null, null)
                .emitDelta(conversationId, "正在排查，请稍候…\n\n");

        long t = System.currentTimeMillis();
        // 框架主线：ops 三阶段 workflow（查日志 → 检索/生成或纠正 → 校验收尾），执行权在框架引擎
        OpsRunner.OpsAnswer answer;
        try {
            answer = opsRunner.run(question, mergedSlots, conversationId);
        } finally {
            opsLease.close();
        }
        TraceView trace = answer.trace();

        log.info("[对话编排] agent 分支完成: type={}, kind={}, 耗时={}ms, llm调用={}次",
                agentType, answer.kind(), System.currentTimeMillis() - t,
                trace != null ? trace.getLlmCallCount() : 0);

        // trace 事件（回答/追问前发出，前端对照面板复用）
        if (trace != null) {
            deliveryPortFactory.begin(sink, conversationId, question, otelTraceId, agentType, trace)
                    .emitTrace(conversationId, trace);
        }

        switch (answer.kind()) {
            case CLARIFY -> handleClarify(answer.text(), question, conversationId, sink, otelTraceId, agentType, trace);
            case DIRECT -> streamDirectAnswer(answer.text(), question, conversationId, sink, otelTraceId, agentType, trace);
            case ESCALATE -> handleEscalate(answer.text(), question, conversationId, sink, otelTraceId, agentType, trace);
            case WITH_CONTEXT -> streamDirectAnswer(answer.text(), question, conversationId, sink, otelTraceId, agentType, trace);
        }
    }

    /** 追问中断：clarify 事件（结构化缺失槽位）→ assistant 消息落库 → 会话状态 AWAITING_USER → meta → 结束。 */
    private void handleClarify(String text, String question, String conversationId,
                               S sink, String otelTraceId, String paradigm, TraceView trace) {
        deliveryPortFactory.begin(sink, conversationId, question, otelTraceId, paradigm, trace)
                .emitClarify(conversationId, text, paradigm, null);
    }

    /** 直答：诊断结论/正确报文不经 RAG 再生成，分段流式输出 + 落库 + meta。 */
    private void streamDirectAnswer(String text, String question, String conversationId,
                                    S sink, String otelTraceId, String paradigm, TraceView trace) {
        deliveryPortFactory.begin(sink, conversationId, question, otelTraceId, paradigm, trace)
                .emitDirect(conversationId, text, paradigm);
    }

    /** replan 升级：说明卡住原因并转追问文案（会话已由 agent 保存为 AWAITING_USER）。 */
    private void handleEscalate(String text, String question, String conversationId,
                                S sink, String otelTraceId, String paradigm, TraceView trace) {
        deliveryPortFactory.begin(sink, conversationId, question, otelTraceId, paradigm, trace)
                .emitEscalate(conversationId, text, paradigm, null);
    }
}
