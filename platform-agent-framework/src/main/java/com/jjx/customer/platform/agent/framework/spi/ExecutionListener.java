package com.jjx.customer.platform.agent.framework.spi;

import com.jjx.customer.platform.agent.framework.node.AgentInvocation;
import com.jjx.customer.platform.agent.framework.plan.ExecutionPlan;
import com.jjx.customer.platform.agent.framework.result.ExecutionResult;

/**
 * 执行生命周期监听器（观察者模式）：订阅引擎/骨架的生命周期与阶段事件。
 *
 * <p>框架内建两个实现，把"横切落库/推送"从引擎里挪出来：</p>
 * <ul>
 *   <li>{@code session.SessionRecordingListener} —— 出口落会话（CLARIFY → AWAITING_USER，终态 → DONE）；</li>
 *   <li>使用方自定义（观测推送 / 指标 / 审计 / 轨迹外送）——实现本接口并注册即可，引擎不感知。</li>
 * </ul>
 *
 * <p>回调不得抛异常（引擎兜底捕获，异常只记不影响主链路）；顺序由 {@link Ordered#order()} 决定。</p>
 */
public interface ExecutionListener extends Ordered {

    /** 引擎启动（装配完成后一次）。 */
    default void onEngineStart() {
    }

    /** 引擎停止（冲刷/释放）。 */
    default void onEngineStop() {
    }

    /** 顶层执行开始。 */
    default void onExecutionStart(ExecutionPlan plan, AgentInvocation invocation) {
    }

    /** 骨架阶段推进。 */
    default void onPhase(ExecutionPhase phase, ExecutionPlan plan, String detail) {
    }

    /** 执行结束（含 CLARIFY / ESCALATE 等终态；会话与观测在此落库）。 */
    default void onExecutionEnd(ExecutionPlan plan, ExecutionResult result) {
    }

    /** 执行抛异常（子 Agent 成环/深度超限、工具失败冒泡等）。 */
    default void onExecutionError(ExecutionPlan plan, Throwable error) {
    }
}
