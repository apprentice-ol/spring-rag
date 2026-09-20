package com.jjx.customer.platform.business.trace;

import com.agentframework.engine.core.ExecutionTraceStep;
import com.agentframework.engine.core.RunResult;
import com.jjx.customer.platform.business.engine.AgentFingerprint;
import com.jjx.customer.platform.business.trace.model.TraceView;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 引擎轨迹 → 业务轨迹映射（前端 SSE trace 事件与落库沿用既有结构）。
 *
 * <p>替代旧内核 {@code ExecutionResult.trace()} 的映射：步骤来自
 * {@code RunResult.executionTrace}（逐节点状态/耗时/输出/工具调用/槽位写入），
 * 结构化元数据（节点类型 / 槽位写入 / 工具调用 / token / 模型 / 路由说明）经
 * {@code detail} 通道下发——前端轨迹树据此按「动作」范式渲染（think+act 合并
 * 工具条目、槽位芯片、阶段分组），旧数据无 detail 时自动退化为纯 action 时间线。</p>
 *
 * <p>LLM 调用数优先取预算槽 {@code llm_calls}（ops/react 线自建计数），无预算槽时按
 * 轨迹里有 token 用量的步数兜底（knowledge 线零 LLM 恒为 0）。</p>
 *
 * <p>执行指纹三元组（agent/workflow/promptHash）由 runner 侧 {@link AgentFingerprint}
 * 传入——SSE 事件、sa_agent_trace、eval 的 trace jsonb 共用本结构（不变量 5）。</p>
 */
public final class EngineTraceMapper {

    /** 预算槽名（ops / react 线的 LLM 调用自建计数）。 */
    static final String LLM_CALLS_SLOT = "llm_calls";

    private EngineTraceMapper() {
    }

    /**
     * @param result      引擎运行结果
     * @param fingerprint 执行指纹（agentId/workflowId/promptHash）
     * @return 业务轨迹视图
     */
    public static TraceView toBusinessTrace(RunResult result, AgentFingerprint fingerprint) {
        String agentId = fingerprint == null ? "" : fingerprint.agentId();
        String workflowId = fingerprint == null ? null : fingerprint.workflowId();
        String promptHash = fingerprint == null ? null : fingerprint.promptHash();
        long now = System.currentTimeMillis();
        // 轨迹起点：优先第一步的绝对时间戳（真实执行开始，见 ExecutionTraceStep.startedAtMs）；
        // 旧内核产出的轨迹步没有时间戳时退回「此刻 - 整轮耗时」反推——本方法是在整轮跑完之后
        // 才被调用的，直接取此刻会把总耗时算成「映射 → 落库」的间隔（实测 12559 vs 14009 的
        // 自相矛盾读数即源于此）。RunResult.duration() 由 DefaultEngine 用 nanoTime 记下，是真实整轮耗时。
        TraceView trace = new TraceView(agentId, workflowId, promptHash, traceAnchorMs(result, now));
        // TraceView.stepDetail 按「now - stepStartMs」算单步耗时，所以要传的是能让差值
        // 等于真实耗时值的时刻（此刻倒推该步自己的耗时）。startedAtMs 只进 detail 通道
        // 作绝对时间记录，不参与该差值——直接传它会把「该步结束到本次映射」的间隔也算进单步耗时。
        for (ExecutionTraceStep step : result.executionTrace()) {
            trace.stepDetail(step.nodeId(), null, queryOf(step), step.output(),
                    now - Math.max(0, step.durationMs()), stepDetailOf(step));
        }
        int llmCalls = llmCallsOf(result);
        for (int i = 0; i < llmCalls; i++) {
            trace.incrementLlmCall();
        }
        return trace;
    }

    /**
     * 结构化步元数据（detail 通道）：节点类型 / 槽位写入 / 工具调用 / token / 模型 /
     * 路由说明 / 失败原因。key 稳定（前端契约），值缺省时省略不写。
     */
    private static Map<String, Object> stepDetailOf(ExecutionTraceStep step) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("nodeType", step.nodeType() == null ? null : step.nodeType().name());
        if (step.startedAtMs() > 0) {
            detail.put("startedAtMs", step.startedAtMs());
        }
        if (step.terminalKind() != null) {
            detail.put("terminalKind", step.terminalKind().name());
        }
        if (step.slotWrites() != null && !step.slotWrites().isEmpty()) {
            detail.put("slotWrites", step.slotWrites());
        }
        if (step.toolCall() != null) {
            detail.put("toolCall", step.toolCall());
        }
        if (step.usage() != null && step.usage().total() > 0) {
            detail.put("promptTokens", step.usage().promptTokens());
            detail.put("completionTokens", step.usage().completionTokens());
        }
        if (step.model() != null) {
            detail.put("model", step.model());
        }
        if (step.routeKind() != null) {
            detail.put("routeKind", step.routeKind());
        }
        if (step.routeDetail() != null && !step.routeDetail().isBlank()) {
            detail.put("routeDetail", step.routeDetail());
        }
        if (step.status() != null) {
            detail.put("status", step.status().name());
        }
        if (step.error() != null && !step.error().isBlank()) {
            detail.put("error", step.error());
        }
        return detail;
    }

    /**
     * 轨迹起点：第一步的绝对时间戳优先（真实执行开始）；无时间戳（旧内核轨迹）时
     * 用「此刻 − 整轮耗时」反推。
     */
    private static long traceAnchorMs(RunResult result, long now) {
        List<ExecutionTraceStep> steps = result.executionTrace();
        if (steps != null && !steps.isEmpty() && steps.get(0).startedAtMs() > 0) {
            return steps.get(0).startedAtMs();
        }
        return now - engineDurationMsOf(result);
    }

    /**
     * 引擎口径的整轮耗时（DefaultEngine 在 RunResult 上用 nanoTime 记录）。
     * <p>取不到时退化为各步耗时之和——那是真实耗时的下限（不含节点之间的间隙），
     * 但远好于把「映射 → 落库」的间隔当成整轮耗时。两者都没有（空轨迹）返回 0，
     * 此时起点即映射时刻，总耗时退化为迁移前的行为。</p>
     */
    private static long engineDurationMsOf(RunResult result) {
        Duration duration = result.duration();
        if (duration != null && !duration.isZero() && !duration.isNegative()) {
            return duration.toMillis();
        }
        List<ExecutionTraceStep> steps = result.executionTrace();
        return steps == null ? 0L : steps.stream().mapToLong(ExecutionTraceStep::durationMs).sum();
    }

    /** LLM 调用数：预算槽优先，无则按轨迹里带 token 用量的步计数。 */
    private static int llmCallsOf(RunResult result) {
        Map<String, Object> slots = result.slots();
        if (slots != null && slots.get(LLM_CALLS_SLOT) instanceof Number number) {
            return number.intValue();
        }
        return (int) result.executionTrace().stream()
                .filter(step -> step.usage() != null && step.usage().total() > 0)
                .count();
    }

    /** 工具步的检索 query（Act 执行器 toolCall.arguments.query）→ inputSummary：react 多轮检索序列的展示数据源。 */
    private static String queryOf(ExecutionTraceStep step) {
        Map<String, Object> toolCall = step.toolCall();
        if (toolCall == null || !(toolCall.get("arguments") instanceof Map<?, ?> args)) {
            return null;
        }
        Object query = args.get("query");
        return query == null ? null : String.valueOf(query);
    }
}
