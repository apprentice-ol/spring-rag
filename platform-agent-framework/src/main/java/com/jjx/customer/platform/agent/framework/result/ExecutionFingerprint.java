package com.jjx.customer.platform.agent.framework.result;

/**
 * 执行指纹：agent + workflow + prompt 内容 hash + release 凭证。
 *
 * <p>进三处：trace 首步、缓存 key、eval 记录——没有它，任何对照实验都无法归因，
 * 改动 prompt 也无法让缓存自动失效。</p>
 *
 * @param agentId     Agent 标识
 * @param workflowId  Workflow 标识
 * @param promptHash  本次执行实际使用的 prompt 内容摘要（三层增强后的快照）
 * @param releasesSpec 可重建凭证（各层包 release 指纹；无绑定时为 null）
 */
public record ExecutionFingerprint(String agentId,
                                   String workflowId,
                                   String promptHash,
                                   String releasesSpec) {
}
