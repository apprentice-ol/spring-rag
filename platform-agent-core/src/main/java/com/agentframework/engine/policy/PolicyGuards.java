package com.agentframework.engine.policy;

import com.agentframework.crosscutting.guard.Guard;
import com.agentframework.crosscutting.guard.GuardContext;
import com.agentframework.crosscutting.guard.GuardDecision;
import com.agentframework.crosscutting.guard.GuardDeniedException;
import com.agentframework.crosscutting.guard.GuardPhase;
import com.agentframework.crosscutting.guard.LoopBreakException;
import com.agentframework.definition.policy.GuardPolicy;
import com.agentframework.runtime.event.Event;
import com.agentframework.runtime.event.EventBus;
import com.agentframework.runtime.event.Topics;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 守卫执行器：在 {@link com.agentframework.crosscutting.guard.GuardChain} 的基础上补上
 * 按守卫解析的失败语义（默认失败关闭），并保证失败开放必须留痕。
 */
public final class PolicyGuards {

    private PolicyGuards() {
    }

    /**
     * 执行守卫链。
     *
     * @param policy  解析结果
     * @param context 守卫上下文
     * @param events  事件总线，可为 null
     * @return 最终决策
     */
    public static GuardDecision evaluate(ResolvedPolicy policy, GuardContext context, EventBus events) {
        GuardContext current = context;
        GuardDecision last = GuardDecision.allow();
        for (Guard guard : policy.guards()) {
            if (!guard.supports(current)) {
                continue;
            }
            GuardDecision decision;
            try {
                decision = guard.check(current);
            } catch (RuntimeException e) {
                if (policy.failureModeOf(guard.name()) == GuardPolicy.FailureMode.DENY) {
                    return GuardDecision.deny("守卫 '" + guard.name() + "' 执行异常（失败关闭）："
                            + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                publishFailOpen(events, context, guard.name(), e);
                continue;
            }
            if (decision == null) {
                continue;
            }
            switch (decision) {
                case GuardDecision.Transform transform -> {
                    current = current.withPayload(transform.payload());
                    last = transform;
                }
                case GuardDecision.Allow ignored -> {
                    // 放行，继续检查后续守卫
                }
                case GuardDecision.Deny deny -> {
                    return deny;
                }
                case GuardDecision.AskApproval ask -> {
                    return ask;
                }
                case GuardDecision.BreakLoop breakLoop -> {
                    return breakLoop;
                }
            }
        }
        return last;
    }

    /**
     * 执行守卫链并在拒绝时抛出异常。
     *
     * @param policy  解析结果
     * @param phase   挂载点
     * @param context 守卫上下文
     * @param events  事件总线，可为 null
     * @return 允许通过的载荷
     */
    public static Object require(ResolvedPolicy policy, GuardPhase phase, GuardContext context, EventBus events) {
        GuardDecision decision = evaluate(policy, context, events);
        return switch (decision) {
            case GuardDecision.Allow ignored -> context.payload();
            case GuardDecision.Transform transform -> transform.payload();
            case GuardDecision.Deny deny -> throw new GuardDeniedException("policy-chain", phase, deny.reason());
            case GuardDecision.AskApproval ask ->
                    throw new GuardDeniedException("policy-chain", phase, "需要人工审批：" + ask.reason());
            case GuardDecision.BreakLoop breakLoop -> throw new LoopBreakException(breakLoop.reason());
        };
    }

    /**
     * 发布失败开放事件。
     *
     * @param events    事件总线
     * @param context   守卫上下文
     * @param guardName 守卫名
     * @param cause     异常
     */
    private static void publishFailOpen(EventBus events, GuardContext context, String guardName, Throwable cause) {
        if (events == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("guard", guardName);
        payload.put("phase", context.phase() == null ? null : context.phase().name());
        payload.put("error", cause.getClass().getSimpleName() + ": " + cause.getMessage());
        events.publish(Event.of(Topics.GUARD_FAILED_OPEN, context.sessionId(), payload));
    }
}
