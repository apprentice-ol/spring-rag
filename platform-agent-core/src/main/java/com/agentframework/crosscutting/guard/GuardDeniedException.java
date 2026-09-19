package com.agentframework.crosscutting.guard;

/**
 * 守卫拒绝异常：守卫判定不允许继续执行时抛出，由引擎转换为会话失败或挂起。
 */
public class GuardDeniedException extends RuntimeException {

    private final String guardName;
    private final GuardPhase phase;

    /**
     * @param guardName 做出拒绝决策的守卫名称
     * @param phase     挂载点
     * @param reason    拒绝原因
     */
    public GuardDeniedException(String guardName, GuardPhase phase, String reason) {
        super("守卫 " + guardName + " 在 " + phase + " 阶段拒绝执行：" + reason);
        this.guardName = guardName;
        this.phase = phase;
    }

    /** @return 守卫名称 */
    public String guardName() {
        return guardName;
    }

    /** @return 挂载点 */
    public GuardPhase phase() {
        return phase;
    }
}
