package com.jjx.customer.platform.agent.framework.session;

import com.jjx.customer.platform.agent.framework.plan.ExecutionPlan;
import com.jjx.customer.platform.agent.framework.result.ClarifyInfo;
import com.jjx.customer.platform.agent.framework.result.ExecutionResult;
import com.jjx.customer.platform.agent.framework.result.OutcomeKind;
import com.jjx.customer.platform.agent.framework.spi.ExecutionListener;

/**
 * 会话落库监听器（框架内建）：把执行出口的结果写回 {@link SessionStore}。
 *
 * <p>会话链路的<b>唯一写入点</b>——编排层只读（恢复优先于路由）+ 占位，引擎侧由本监听器落库：</p>
 * <ul>
 *   <li>CLARIFY → AWAITING_USER（缺哪些槽位/停在哪个阶段取自 {@link ClarifyInfo}，使用方不再重算）；</li>
 *   <li>其余终态（DIRECT / WITH_CONTEXT / ESCALATE）→ DONE。</li>
 * </ul>
 *
 * <p>无 sessionId（单轮 REST 调试等）直接跳过；异常由引擎兜底捕获，不影响主链路。</p>
 */
public final class SessionRecordingListener implements ExecutionListener {

    /** 先于观测/推送类监听器（会话是可恢复性的前置信息）。 */
    public static final int ORDER = 100;

    private final SessionStore sessionStore;

    public SessionRecordingListener(SessionStore sessionStore) {
        this.sessionStore = sessionStore == null ? SessionStore.NOOP : sessionStore;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public void onExecutionEnd(ExecutionPlan plan, ExecutionResult result) {
        String sessionId = plan.request().sessionId();
        if (sessionId == null || sessionStore == SessionStore.NOOP) {
            return;
        }
        if (result.kind() == OutcomeKind.CLARIFY) {
            ClarifyInfo clarify = result.clarifyInfo();
            sessionStore.saveAwaitingUser(AgentSessionState.awaitingUser(sessionId, plan.agent().id(),
                    clarify == null ? null : clarify.stage(),
                    clarify == null ? null : clarify.slots(),
                    clarify == null ? null : clarify.missingSlots(),
                    result.text()));
            return;
        }
        sessionStore.complete(sessionId);
    }
}
