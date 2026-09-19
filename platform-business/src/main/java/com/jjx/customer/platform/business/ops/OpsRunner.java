package com.jjx.customer.platform.business.ops;

import com.jjx.customer.platform.business.trace.EngineTraceMapper;

import com.agentframework.definition.workflow.HumanRequest;
import com.agentframework.engine.core.Engine;
import com.agentframework.engine.core.RunResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.agentframework.runtime.session.Input;
import com.agentframework.runtime.session.Session;
import com.agentframework.runtime.session.StartOptions;
import com.jjx.customer.platform.business.engine.AgentCatalog;
import com.jjx.customer.platform.business.engine.PromptFingerprintResolver;
import com.jjx.customer.platform.business.engine.AgentFingerprint;
import com.jjx.customer.platform.business.engine.outcome.OutcomeKind;
import com.jjx.customer.platform.business.engine.outcome.RunOutcomeMapper;
import com.jjx.customer.platform.business.ops.AutonomyLevel;
import com.jjx.customer.platform.business.ops.workflow.OpsDiagnoseWorkflowFactory;
import com.jjx.customer.platform.business.ops.slot.OpsSlotCatalog;
import com.jjx.customer.platform.business.ops.node.AskMissingExecutor;
import com.jjx.customer.platform.business.ops.node.SlotExtractExecutor;
import com.jjx.customer.platform.business.session.AgentSessionState;
import com.jjx.customer.platform.business.session.AgentSessionServiceImpl;
import com.jjx.customer.platform.business.trace.model.TraceView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 运维诊断主线执行器（对话链路与 REST 端点共用，签名与返回契约完全不变）。
 *
 * <p>内部执行权在新内核引擎（图 = {@code ops_diagnose_v2}，装配见
 * {@code AgentEngineConfiguration}）：</p>
 * <ul>
 *   <li>会话恢复：sa_agent_session 有已确认槽位且引擎会话（id = {@code ops-<conversationId>}
 *       确定性派生）可 load → {@code engine.resume}（答复写 user_clarify 槽，问齐与环内追问
 *       共用恢复通道）；引擎侧无记录（如旧版本挂起的存量会话）→ 带已确认槽位重开新会话；</li>
 *   <li>出口映射：{@link RunOutcomeMapper}（挂起→CLARIFY / 升级→ESCALATE / 收尾→DIRECT）；</li>
 *   <li>澄清落库：挂起写 AWAITING_USER（已确认槽位 + 缺失清单）、终态写 DONE——
 *       替代旧内核的 {@code SessionRecordingListener}；REST 单轮（sessionId=null）用
 *       ephemeral 会话，不落库。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpsRunner {

    /** 一次诊断的执行产物（交付由调用方决定：SSE / REST 投影）。 */
    public record OpsAnswer(OutcomeKind kind, String text, TraceView trace,
                            HumanRequest humanRequest) {

        /** 兼容旧构造（无人在环请求）。 */
        public OpsAnswer(OutcomeKind kind, String text, TraceView trace) {
            this(kind, text, trace, null);
        }
    }

    private final Engine agentEngine;
    private final AgentSessionServiceImpl sessionService;
    private final PromptFingerprintResolver fingerprintResolver;
    private final ObjectMapper objectMapper;

    /**
     * @param sessionSlots 会话已确认槽位（可空：单轮调用 / 首轮）
     * @param sessionId    会话标识（非空 = 出口落澄清会话；REST 单轮为 null）
     */
    public OpsAnswer run(String question, Map<String, String> sessionSlots, String sessionId) {
        RunResult result = execute(question, sessionSlots, sessionId);
        OutcomeKind kind = RunOutcomeMapper.kindOf(result);
        if (sessionId != null && !sessionId.isBlank()) {
            if (kind == OutcomeKind.CLARIFY) {
                sessionService.saveAwaitingUser(clarifyStateOf(sessionId, result));
            } else {
                sessionService.complete(sessionId);
            }
        }
        AgentCatalog.Entry ops = AgentCatalog.OPS;
        TraceView trace = EngineTraceMapper.toBusinessTrace(result, new AgentFingerprint(
                ops.id(), ops.workflowId(), fingerprintResolver.promptHash(ops.id())));
        return new OpsAnswer(kind, textOf(result), trace, humanRequestOf(result));
    }

    /**
     * 挂起时的结构化决议请求（DECIDE 决策移交 / 未来的 APPROVE、CONFIRM）。
     *
     * <p>槽位里没有 {@code pending_human_request}（普通问齐挂起 / 非挂起出口）返回 null，
     * 交付侧按现状纯文本渲染——CLARIFY 问齐卡片不依赖本字段。</p>
     */
    private HumanRequest humanRequestOf(RunResult result) {
        if (result.slots() == null) {
            return null;
        }
        Object pending = result.slots().get(HumanRequest.PENDING_SLOT);
        if (!(pending instanceof String json) || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, HumanRequest.class);
        } catch (Exception e) {
            log.warn("[ops] 决议请求反序列化失败（按纯文本交付）: {}", e.getMessage());
            return null;
        }
    }

    private RunResult execute(String question, Map<String, String> sessionSlots, String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            String engineSessionId = engineSessionIdOf(sessionId);
            Optional<Session> restored = agentEngine.contexts().load(engineSessionId);
            if (restored.isPresent() && restored.get().state()
                    == com.agentframework.runtime.session.SessionState.SUSPENDED) {
                Map<String, Object> initial = new LinkedHashMap<>();
                if (sessionSlots != null) {
                    initial.putAll(sessionSlots);
                }
                initial.put(AskMissingExecutor.USER_CLARIFY_SLOT, question == null ? "" : question);
                withAutonomy(initial, sessionId);
                return agentEngine.resume(restored.get(),
                        new Input(question == null ? "" : question, Map.of(), initial));
            }
            // 引擎无挂起记录（新对话 / 存量旧会话跨版本）：带已确认槽位重开
            Map<String, Object> prefill = new LinkedHashMap<>();
            if (sessionSlots != null) {
                prefill.putAll(sessionSlots);
            }
            withAutonomy(prefill, sessionId);
            Session session = agentEngine.startSession(
                    agentEngine.loadAgent(AgentCatalog.OPS.id(), "latest"),
                    StartOptions.defaults().withSessionId(engineSessionId));
            return agentEngine.run(session, new Input(question, Map.of(), prefill));
        }
        // REST 单轮：ephemeral 会话（不落引擎会话表）
        Map<String, Object> prefill = new LinkedHashMap<>();
        if (sessionSlots != null) {
            prefill.putAll(sessionSlots);
        }
        return agentEngine.run(AgentCatalog.OPS.id(), new Input(question, Map.of(), prefill));
    }

    /**
     * 档位缺省注入（人在环中 P3）：本轮槽位没带（前端旋钮/TraceId 短路等路径）→ 取会话记录，
     * 记录也没有 → 不注入，{@code AutoResolveExecutor} 侧按缺省 L2 同口径处理。
     *
     * @param slots     引擎输入槽位（就地补 {@code autonomy_level}）
     * @param sessionId 会话标识
     */
    private void withAutonomy(Map<String, Object> slots, String sessionId) {
        if (!slots.containsKey(AutonomyLevel.SLOT)) {
            slots.put(AutonomyLevel.SLOT, sessionService.autonomyOf(sessionId).name());
        }
    }

    /** CLARIFY 落库状态：已确认槽位取引擎快照里的业务槽，缺失清单按目录重算（与 AskMissing 同口径）。 */
    private AgentSessionState clarifyStateOf(String sessionId, RunResult result) {
        Map<String, String> confirmed = new LinkedHashMap<>();
        if (result.slots() != null) {
            for (String name : OpsSlotCatalog.NAMES) {
                Object value = result.slots().get(name);
                if (value != null && !String.valueOf(value).isBlank()) {
                    confirmed.put(name, String.valueOf(value));
                }
            }
        }
        List<String> missing = new ArrayList<>(
                SlotExtractExecutor.missingRequired(confirmed, Map.of()));
        String pendingAskSlots = result.slots() == null ? ""
                : String.valueOf(result.slots()
                        .getOrDefault("pending_ask_slots", ""));
        if (missing.isEmpty() && pendingAskSlots != null && !pendingAskSlots.isBlank()
                && !"null".equals(pendingAskSlots)) {
            missing = List.of(pendingAskSlots.split(","));
        }
        String stage = result.suspendedNode() == null ? OpsDiagnoseWorkflowFactory.COLLECT_SLOTS_NODE
                : result.suspendedNode();
        // 档位随会话落库（本轮由前端旋钮 / 用户顺带调档 / 上轮沿用而来），下一轮无显式传入时沿用
        String autonomy = result.slots() == null ? ""
                : String.valueOf(result.slots().getOrDefault(AutonomyLevel.SLOT, ""));
        return new AgentSessionState(sessionId, AgentCatalog.OPS.id(), stage,
                AgentSessionState.Status.AWAITING_USER, confirmed, missing, null,
                autonomy.isBlank() || "null".equals(autonomy) ? null : autonomy, currentTraceId());
    }

    /**
     * 当前 OTel traceId（诊断链 id 的取值）。
     *
     * <p>同一诊断任务的追问轮会沿用它（见 ChatOrchestrator），所以一次诊断从追问到结论
     * 是一条链而不是 N 段孤立的轨迹；落库侧只在首轮写入（{@code AgentSessionServiceImpl}）。</p>
     *
     * @return traceId；无有效 span 时 null
     */
    private static String currentTraceId() {
        var ctx = io.opentelemetry.api.trace.Span.current().getSpanContext();
        return ctx.isValid() ? ctx.getTraceId() : null;
    }

    /** 引擎会话 id 派生规则（对话 conversationId → 引擎会话，恢复链不断）。 */
    public static String engineSessionIdOf(String conversationId) {
        return "ops-" + conversationId;
    }

    private static String textOf(RunResult result) {
        String text = result.output();
        return text == null || text.isBlank() ? "诊断完成（无结论文本，详见轨迹）。" : text;
    }
}
