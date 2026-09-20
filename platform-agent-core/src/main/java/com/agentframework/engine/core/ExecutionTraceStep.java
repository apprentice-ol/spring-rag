package com.agentframework.engine.core;

import com.agentframework.definition.node.NodeType;
import com.agentframework.definition.node.TerminalKind;
import com.agentframework.infra.modelgateway.Usage;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 执行轨迹中的单步记录：一个节点从进入到路由离开的完整快照。
 *
 * <p>与 {@link RunResult#visitedNodes()} 的裸 id 列表不同，轨迹逐步保留状态、耗时、
 * 槽位写入与路由决策，用于审计、调试与前端回放。</p>
 *
 * @param index        步号，从 1 开始
 * @param nodeId       节点 id
 * @param nodeType     节点类型
 * @param status       执行状态
 * @param arrivedVia   进入该节点的边，形如 {@code a->b}，起点为 null 时为空串
 * @param durationMs   节点执行耗时（毫秒）
 * @param startedAtMs  节点开始的墙钟时刻（epoch 毫秒）。绝对时间戳是轨迹契约的一部分——
 *                     只有相对耗时时，消费方只能用「映射时刻倒推」补偿，每一步的时间轴都是错的
 * @param endedAtMs    节点结束的墙钟时刻（epoch 毫秒）
 * @param output       节点输出
 * @param slotWrites   本步写回的槽位
 * @param usage        本步消耗的 token
 * @param model        本步调用的模型名；非 LLM 步为 null
 * @param toolCall     本步的工具调用记录（name / arguments / ok / error）；非工具步为 null
 * @param routeKind    离开方式：dynamic / deterministic / static / terminal / suspended / failed / loop_break
 * @param routeTo      路由目标节点 id，结束、挂起或失败时为 null
 * @param routeDetail  路由补充说明（选中的边、被拒原因、中断原因等）
 * @param error        失败原因，成功时为 null
 * @param terminalKind 节点声明的终态语义（见 {@link TerminalKind}）；未声明的节点为 null。
 *                     出口推导读它而不是靠节点命名约定——约定换个名字就静默失效
 */
public record ExecutionTraceStep(
        int index,
        String nodeId,
        NodeType nodeType,
        NodeStatus status,
        String arrivedVia,
        long durationMs,
        long startedAtMs,
        long endedAtMs,
        String output,
        Map<String, Object> slotWrites,
        Usage usage,
        String model,
        Map<String, Object> toolCall,
        String routeKind,
        String routeTo,
        String routeDetail,
        String error,
        TerminalKind terminalKind) {

    /** 动态边被采纳。 */
    public static final String ROUTE_DYNAMIC = "dynamic";
    /** 条件节点等显式指定下一节点。 */
    public static final String ROUTE_DETERMINISTIC = "deterministic";
    /** 按静态出边（条件/守卫/优先级）推导。 */
    public static final String ROUTE_STATIC = "static";
    /** 无下一节点，工作流结束。 */
    public static final String ROUTE_TERMINAL = "terminal";
    /** 节点挂起等待外部输入。 */
    public static final String ROUTE_SUSPENDED = "suspended";
    /** 节点失败。 */
    public static final String ROUTE_FAILED = "failed";
    /** 循环中断，跳过回边。 */
    public static final String ROUTE_LOOP_BREAK = "loop_break";

    public ExecutionTraceStep {
        arrivedVia = arrivedVia == null ? "" : arrivedVia;
        output = output == null ? "" : output;
        slotWrites = slotWrites == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(slotWrites));
        usage = usage == null ? Usage.zero() : usage;
        model = model == null || model.isBlank() ? null : model;
        toolCall = toolCall == null || toolCall.isEmpty()
                ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(toolCall));
    }

    /**
     * @param newIndex 新步号（跨轮累计时重编号）
     * @return 覆盖步号后的轨迹步
     */
    public ExecutionTraceStep withIndex(int newIndex) {
        return new ExecutionTraceStep(newIndex, nodeId, nodeType, status, arrivedVia, durationMs, startedAtMs,
                endedAtMs, output, slotWrites, usage, model, toolCall, routeKind, routeTo, routeDetail, error,
                terminalKind);
    }

    /**
     * @return 可序列化文档（空字段剔除，便于响应瘦身；空轨迹步仍保留基础字段）
     */
    public Map<String, Object> toDocument() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("index", index);
        document.put("nodeId", nodeId);
        document.put("nodeType", nodeType.name());
        document.put("status", status.name());
        if (!arrivedVia.isEmpty()) {
            document.put("arrivedVia", arrivedVia);
        }
        document.put("durationMs", durationMs);
        if (startedAtMs > 0) {
            document.put("startedAtMs", startedAtMs);
        }
        if (endedAtMs > 0) {
            document.put("endedAtMs", endedAtMs);
        }
        if (terminalKind != null) {
            document.put("terminalKind", terminalKind.name());
        }
        if (!output.isEmpty()) {
            document.put("output", output);
        }
        if (!slotWrites.isEmpty()) {
            document.put("slotWrites", slotWrites);
        }
        if (usage.total() > 0) {
            document.put("tokens", usage.total());
            // 拆分明细：只有总数时「这一步贵在输入还是输出」答不上来，
            // 而调 prompt 与调输出上限恰恰是最常见的两个动作
            document.put("promptTokens", usage.promptTokens());
            document.put("completionTokens", usage.completionTokens());
        }
        if (model != null) {
            document.put("model", model);
        }
        if (toolCall != null) {
            document.put("toolCall", toolCall);
        }
        if (routeKind != null) {
            document.put("routeKind", routeKind);
        }
        if (routeTo != null) {
            document.put("routeTo", routeTo);
        }
        if (routeDetail != null && !routeDetail.isBlank()) {
            document.put("routeDetail", routeDetail);
        }
        if (error != null) {
            document.put("error", error);
        }
        return document;
    }

    /**
     * @return 可读的一行轨迹，例如 {@code #3 tool:search [TOOL] 12ms -> step_answer (dynamic)}
     */
    public String describe() {
        StringBuilder text = new StringBuilder("#").append(index).append(' ').append(nodeId)
                .append(" [").append(nodeType).append(']').append(' ').append(durationMs).append("ms");
        if (status != NodeStatus.COMPLETED) {
            text.append(' ').append(status);
        }
        if (usage.total() > 0) {
            text.append(" tokens=").append(usage.total());
        }
        if (model != null) {
            text.append(" model=").append(model);
        }
        if (!slotWrites.isEmpty()) {
            text.append(" writes=").append(slotWrites.keySet());
        }
        if (routeTo != null) {
            text.append(" -> ").append(routeTo).append(" (").append(routeKind).append(')');
        } else if (routeDetail != null && !routeDetail.isBlank()) {
            text.append(" (").append(routeDetail).append(')');
        }
        if (error != null) {
            text.append(" error=").append(error);
        }
        return text.toString();
    }
}
