package com.agentframework.crosscutting.guard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 守卫链：按 order 顺序执行守卫，遇到第一个非放行决策即短路返回。
 *
 * <p>多个 {@link GuardDecision.Transform} 会依次叠加，最终由链返回最后一次改造结果。</p>
 */
public final class GuardChain {

    private final List<Guard> guards = new ArrayList<>();

    /** 创建空链。 */
    public GuardChain() {
    }

    /**
     * @param guards 初始守卫集合
     */
    public GuardChain(List<Guard> guards) {
        if (guards != null) {
            this.guards.addAll(guards);
            this.guards.sort(Comparator.comparingInt(Guard::order));
        }
    }

    /**
     * 追加守卫。
     *
     * @param guard 守卫实现
     * @return 当前链
     */
    public GuardChain add(Guard guard) {
        if (guard != null) {
            guards.add(guard);
            guards.sort(Comparator.comparingInt(Guard::order));
        }
        return this;
    }

    /** @return 链上的守卫快照 */
    public List<Guard> guards() {
        return List.copyOf(guards);
    }

    /**
     * 执行守卫链。
     *
     * @param context 守卫上下文
     * @return 最终决策；全部放行时返回带 {@code Transform} 叠加结果的决策
     */
    public GuardDecision evaluate(GuardContext context) {
        GuardContext current = context;
        GuardDecision last = GuardDecision.allow();
        for (Guard guard : guards) {
            if (!guard.supports(current)) {
                continue;
            }
            GuardDecision decision = guard.check(current);
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
}
