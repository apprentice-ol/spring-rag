package com.jjx.customer.platform.agent.framework.trace;

import java.util.List;

/**
 * 轨迹步骤（观测契约）。
 *
 * @param action    动作标识（阶段名 / 工具名 / 路由 / 护栏 / 预算等）
 * @param thought   思考摘要（可空）
 * @param output    输出摘要（可空）
 * @param status    状态（完成 / 跳过 / 失败 / 出环原因）
 * @param startedAt 开始时间（epoch millis）
 * @param duration  耗时（毫秒）
 * @param children  嵌套子步骤（子 Agent 重入的执行轨迹挂在父的 AGENT_CALL 步骤下；空 = 叶子步骤）
 */
public record AgentStep(String action,
                        String thought,
                        String output,
                        String status,
                        long startedAt,
                        long duration,
                        List<AgentStep> children) {

    public AgentStep {
        children = children == null ? List.of() : List.copyOf(children);
    }

    public AgentStep(String action, String thought, String output, String status,
                     long startedAt, long duration) {
        this(action, thought, output, status, startedAt, duration, List.of());
    }
}
