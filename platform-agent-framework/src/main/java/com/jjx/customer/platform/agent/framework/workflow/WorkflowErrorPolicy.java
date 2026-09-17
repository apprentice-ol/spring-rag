package com.jjx.customer.platform.agent.framework.workflow;

/**
 * 阶段错误策略：失败不静默——每种失败都要有明确处置。
 */
public record WorkflowErrorPolicy(Action action, int retries) {

    public enum Action {
        /** 维持现状：记录失败，继续流程。 */
        AS_IS,
        /** 重试本阶段（至多 retries 次）。 */
        RETRY,
        /** 放弃本阶段，继续后续阶段。 */
        SKIP,
        /** 转升级（交引擎产出 ESCALATE 结果）。 */
        ESCALATE,
        /** 终止流程（直出失败说明）。 */
        FAIL
    }

    public static final WorkflowErrorPolicy AS_IS = new WorkflowErrorPolicy(Action.AS_IS, 0);

    public static WorkflowErrorPolicy retry(int times) {
        return new WorkflowErrorPolicy(Action.RETRY, Math.max(0, times));
    }

    public static WorkflowErrorPolicy skip() {
        return new WorkflowErrorPolicy(Action.SKIP, 0);
    }

    public static WorkflowErrorPolicy escalate() {
        return new WorkflowErrorPolicy(Action.ESCALATE, 0);
    }

    public static WorkflowErrorPolicy fail() {
        return new WorkflowErrorPolicy(Action.FAIL, 0);
    }
}
