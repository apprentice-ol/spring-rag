package com.jjx.customer.platform.business.orchestration;

import com.jjx.ai.llmobservability.observation.logging.TelemetryLogger;
import com.agentframework.definition.workflow.HumanRequest;
import com.jjx.customer.platform.business.ops.AutonomyLevel;
import com.jjx.customer.platform.business.ops.OpsRunner;
import com.jjx.customer.platform.business.ops.slot.OpsSlotCatalog;
import com.jjx.customer.platform.business.runtime.DegradeGuard;
import com.jjx.customer.platform.business.trace.model.TraceView;
import com.jjx.customer.platform.clarify.ClarifyRequest;
import com.jjx.customer.platform.clarify.SlotQuestion;
import com.jjx.customer.platform.delivery.DeliveryPortFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
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
     * @param autonomy     会话自主档位（人在环中 P3 前端旋钮；空 = 由 OpsRunner 按会话记录/缺省补）
     */
    public void runAgentBranch(String agentType, String question, String conversationId, S sink,
                               String otelTraceId,
                               Map<String, String> prefillSlots,
                               Map<String, String> resumedSlots,
                               String autonomy) {
        // 槽位合并：会话已确认值 + 本轮预填（trace_id 等正则提取结果；只保留 ops 目录内槽位）
        Map<String, String> mergedSlots = new LinkedHashMap<>();
        if (resumedSlots != null) {
            mergedSlots.putAll(resumedSlots);
        }
        mergedSlots.putAll(OpsSlotCatalog.sanitized(prefillSlots));
        // 档位显式传入优先（前端旋钮）；空值不覆盖，由 OpsRunner 按会话记录 / 缺省 L2 补
        if (autonomy != null && !autonomy.isBlank()) {
            mergedSlots.put(AutonomyLevel.SLOT, AutonomyLevel.parse(autonomy).name());
        }

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
            case CLARIFY -> handleClarify(answer, question, conversationId, sink, otelTraceId, agentType, trace);
            case DIRECT -> streamDirectAnswer(answer.text(), question, conversationId, sink, otelTraceId, agentType, trace);
            case ESCALATE -> handleEscalate(answer.text(), question, conversationId, sink, otelTraceId, agentType, trace);
            case WITH_CONTEXT -> streamDirectAnswer(answer.text(), question, conversationId, sink, otelTraceId, agentType, trace);
        }
    }

    /**
     * 追问中断：clarify 事件（结构化）→ assistant 消息落库 → 会话状态 AWAITING_USER → meta → 结束。
     * DECIDE 决策移交时带证据与点选项（人在环中），普通问齐仍是缺失槽位清单。
     */
    private void handleClarify(OpsRunner.OpsAnswer answer, String question, String conversationId,
                               S sink, String otelTraceId, String paradigm, TraceView trace) {
        deliveryPortFactory.begin(sink, conversationId, question, otelTraceId, paradigm, trace)
                .emitClarify(conversationId, toClarifyRequest(conversationId, answer), paradigm, null);
    }

    /** HumanRequest（core，槽位反序列化产物）→ ClarifyRequest（shared 交付契约）：结构化字段单向投影。 */
    private static ClarifyRequest toClarifyRequest(String conversationId, OpsRunner.OpsAnswer answer) {
        HumanRequest request = answer.humanRequest();
        if (request == null) {
            return new ClarifyRequest(conversationId, answer.text(), List.of());
        }
        List<String> evidence = request.context() == null
                ? List.of() : request.context().evidence();
        if (request.kind() == HumanRequest.Kind.CLARIFY || request.kind() == HumanRequest.Kind.CONFIRM) {
            // 问齐/确认都分两组投影（CONFIRM 的槽位全有值 → 全落 reviewed）：
            // 待补问题（questions）与「已补全、等你确认」（reviewed，
            // 带值 + 来源角标，前端据此渲染纠正入口）。目录槽 label 空时查目录补问句/提示/候选；
            // 动态槽用模型给的声明。autoNote 仍进 evidence（纯文本兜底与落库文案用）
            List<SlotQuestion> questions = request.slots().stream()
                    .filter(ask -> !ask.hasValue())
                    .map(AgentBranchDispatcher::toSlotQuestion)
                    .toList();
            List<SlotQuestion> reviewed = request.slots().stream()
                    .filter(HumanRequest.SlotAsk::hasValue)
                    .map(AgentBranchDispatcher::toSlotQuestion)
                    .toList();
            // CONFIRM 的「确认，开始排查」也是选项——漏投影的话卡片只剩信息没有动作（实测踩到）
            return new ClarifyRequest(conversationId, request.prompt(), questions, reviewed,
                    request.kind().name(), toChoices(request), evidence, request.allowFreeText());
        }
        // 决策移交：点选项投影成 options，证据进 evidence
        return new ClarifyRequest(conversationId, request.prompt(), List.of(), List.of(),
                request.kind().name(), toChoices(request), evidence, request.allowFreeText());
    }

    /** 点选项投影（DECIDE 的继续/终止、CONFIRM 的确认同口径）。 */
    private static List<ClarifyRequest.ClarifyChoice> toChoices(HumanRequest request) {
        return request.options().stream()
                .map(c -> new ClarifyRequest.ClarifyChoice(c.value(), c.label(), c.description()))
                .toList();
    }

    /** SlotAsk（core）→ SlotQuestion（shared）：目录槽（label 空）查 OpsSlotCatalog 补问句/提示/候选值。 */
    private static SlotQuestion toSlotQuestion(HumanRequest.SlotAsk ask) {
        boolean required = !ask.hasValue();
        if (ask.label() != null && !ask.label().isBlank()) {
            return new SlotQuestion(ask.name(), ask.label(), ask.hint(), required, ask.options(),
                    ask.value(), ask.provenance(), ask.evidence());
        }
        return OpsSlotCatalog.ALL.stream()
                .filter(spec -> spec.name().equals(ask.name())).findFirst()
                .map(spec -> new SlotQuestion(spec.name(), spec.question(), spec.hint(),
                        required, spec.options(), ask.value(), ask.provenance(), ask.evidence()))
                .orElseGet(() -> new SlotQuestion(ask.name(), ask.name(), ask.hint(),
                        required, ask.options(), ask.value(), ask.provenance(), ask.evidence()));
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
