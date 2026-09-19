package com.jjx.customer.platform.business.engine.outcome;

import com.agentframework.engine.core.RunResult;
import java.util.Map;

/**
 * {@link RunResult} → 业务 {@link OutcomeKind} 的等价映射（替代旧内核 {@code ExecutionResult.kind()}）。
 *
 * <p>判定约定（与 ops 诊断图的节点契约对齐，见 {@code business.ops.workflow.OpsDiagnoseWorkflowFactory}）：</p>
 * <ul>
 *   <li>挂起（{@code state=SUSPENDED}，任意执行器 {@code NodeResult.suspended()}）→ {@link OutcomeKind#CLARIFY}，
 *       text = 挂起时的追问文案（{@code RunResult.output}）；</li>
 *   <li>到访升级终态节点（{@code escalate_node}）或槽位带 {@code escalate_reason} → {@link OutcomeKind#ESCALATE}；</li>
 *   <li>执行失败（{@code state=FAILED}）保守按升级交付（诊断链不允许静默截断）；</li>
 *   <li>其余完成态（{@code conclude} 出口）→ {@link OutcomeKind#DIRECT}。</li>
 * </ul>
 * <p>{@link OutcomeKind#WITH_CONTEXT} 不经本映射——knowledge 线检索完成后由
 * {@code KnowledgeRunner} 自行判定（两段式：证据先就绪，生成交付段在引擎外）。</p>
 */
public final class RunOutcomeMapper {

    /** 升级终态节点 id（与 ops 图 {@code TerminalStageModule} 的节点名约定一致）。 */
    public static final String ESCALATE_NODE_ID = "escalate_node";

    /** 升级原因槽位名（escalate 执行器写、SSE/trace 消费）。 */
    public static final String ESCALATE_REASON_SLOT = "escalate_reason";

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
        if (result.visitedNodes() != null && result.visitedNodes().contains(ESCALATE_NODE_ID)) {
            return OutcomeKind.ESCALATE;
        }
        Map<String, Object> slots = result.slots();
        if (slots != null && slots.containsKey(ESCALATE_REASON_SLOT)) {
            return OutcomeKind.ESCALATE;
        }
        if (result.state() == com.agentframework.runtime.session.SessionState.FAILED) {
            return OutcomeKind.ESCALATE;
        }
        return OutcomeKind.DIRECT;
    }
}
