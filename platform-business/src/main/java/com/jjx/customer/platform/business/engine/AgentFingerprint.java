package com.jjx.customer.platform.business.engine;

/**
 * 执行指纹三元组（旧内核 {@code ExecutionFingerprint} 的等价物）。
 *
 * <p>贯穿 sa_agent_trace、SSE trace 事件、答案缓存 key、eval——任何一层 prompt
 * 变化都会改变 promptHash，从而自动失效相关缓存（F6）。</p>
 *
 * @param agentId    Agent 标识（如 ops_diagnose / knowledge / react_loop）
 * @param workflowId 工作流定义 id（如 ops_diagnose_v2）
 * @param promptHash 该 Agent 全层 prompt 生效文本拼接后的 SHA-256（{@code PromptFingerprintResolver} 预计算）
 */
public record AgentFingerprint(String agentId, String workflowId, String promptHash) {
}
