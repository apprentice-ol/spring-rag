package com.agentframework.definition.policy;

import java.util.List;

/**
 * 守卫策略：声明参与执行的 Guard 以及守卫自身异常时的兜底行为。
 *
 * @param refs        守卫引用列表，对应扩展注册表中的 Guard 名称
 * @param failureMode 守卫执行异常时的默认决策，{@link FailureMode#DENY} 为失败关闭（默认）
 */
public record GuardPolicy(List<String> refs, FailureMode failureMode) {

    /** 守卫异常时的兜底语义：DENY 失败关闭（默认），ALLOW 失败开放。 */
    public enum FailureMode {
        DENY,
        ALLOW
    }

    public GuardPolicy {
        refs = List.copyOf(refs == null ? List.of() : refs);
        failureMode = failureMode == null ? FailureMode.DENY : failureMode;
    }

    /** @return 默认策略：不挂载任何守卫，失败关闭 */
    public static GuardPolicy defaults() {
        return new GuardPolicy(List.of(), FailureMode.DENY);
    }

    /**
     * @param refs 守卫引用列表
     * @return 失败关闭的守卫策略
     */
    public static GuardPolicy of(String... refs) {
        return new GuardPolicy(List.of(refs), FailureMode.DENY);
    }

    /**
     * 调整失败语义。
     *
     * @param mode 兜底决策
     * @return 新的策略实例
     */
    public GuardPolicy withFailureMode(FailureMode mode) {
        return new GuardPolicy(refs, mode);
    }
}
