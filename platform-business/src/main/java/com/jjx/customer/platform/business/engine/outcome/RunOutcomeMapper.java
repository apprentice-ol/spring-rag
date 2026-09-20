package com.jjx.customer.platform.business.engine.outcome;

import com.jjx.customer.platform.business.workflow.common.EscalateTerminal;

import com.agentframework.definition.node.TerminalKind;
import com.agentframework.engine.core.RunResult;
import java.util.Map;

/**
 * {@link RunResult} → 业务 {@link OutcomeKind} 的等价映射（替代旧内核 {@code ExecutionResult.kind()}）。
 *
 * <p>判定约定（终态语义优先读节点声明 {@code TerminalKind}，命名/槽位约定仅兜底）：</p>
 * <ul>
 *   <li>挂起（{@code state=SUSPENDED}，任意执行器 {@code NodeResult.suspended()}）→ {@link OutcomeKind#CLARIFY}，
 *       text = 挂起时的追问文案（{@code RunResult.output}）；</li>
 *   <li>轨迹中存在终态声明 {@code ESCALATE} 的步（图声明见 {@code TerminalStageModule} /
 *       {@code KnowledgeReactGraphFactory}），或到访升级终态节点（{@code escalate_node}），
 *       或槽位带 {@code escalate_reason} → {@link OutcomeKind#ESCALATE}；</li>
 *   <li>执行失败（{@code state=FAILED}）保守按升级交付（诊断链不允许静默截断）；</li>
 *   <li>其余完成态（{@code conclude} 出口）→ {@link OutcomeKind#DIRECT}。</li>
 * </ul>
 * <p>{@link OutcomeKind#WITH_CONTEXT} 不经本映射——knowledge 线检索完成后由
 * {@code KnowledgeRunner} 自行判定（两段式：证据先就绪，生成交付段在引擎外）。</p>
 */
public final class RunOutcomeMapper {

    /** 升级终态节点 id（与 ops 图 {@code TerminalStageModule} 的节点名约定一致）。 */
    public static final String ESCALATE_NODE_ID = EscalateTerminal.NODE_ID;

    /** 升级原因槽位名（escalate 执行器写、SSE/trace 消费）。 */
    public static final String ESCALATE_REASON_SLOT = EscalateTerminal.REASON_SLOT;

    private RunOutcomeMapper() {
    }

    /**
     * @param result 引擎一次运行的结果
     * @return 业务出口语义
     */
    public static OutcomeKind kindOf(RunResult result) {
        if (result == null) {
            return OutcomeKind.ESCALATE;
        }
        if (result.suspended() || result.suspendedNode() != null) {
            return OutcomeKind.CLARIFY;
        }
        if (escalated(result)) {
            return OutcomeKind.ESCALATE;
        }
        if (result.state() == com.agentframework.runtime.session.SessionState.CANCELLED) {
            // 用户中止：没有结论，必须与 DIRECT 区分开（否则会被当成一次正常收尾落库）
            return OutcomeKind.CANCELLED;
        }
        if (result.state() == com.agentframework.runtime.session.SessionState.FAILED) {
            return OutcomeKind.ESCALATE;
        }
        return OutcomeKind.DIRECT;
    }

    /**
     * 升级判定，按可靠性排序：终态声明（图定义时显式声明，改名不失效）→ 节点 id 约定 →
     * 升级原因槽位（挂起恢复等路径可能没走完 escalate 节点但已写原因）。
     */
    private static boolean escalated(RunResult result) {
        if (result.executionTrace() != null && result.executionTrace().stream()
                .anyMatch(step -> step.terminalKind() == TerminalKind.ESCALATE)) {
            return true;
        }
        if (result.visitedNodes() != null && result.visitedNodes().contains(ESCALATE_NODE_ID)) {
            return true;
        }
        Map<String, Object> slots = result.slots();
        return slots != null && slots.containsKey(ESCALATE_REASON_SLOT);
    }
}
