package com.jjx.customer.platform.agent.framework.capability;

/**
 * Agent 能力位（声明在 Agent 层，供给在 Workflow 层，开关在配置层）。
 *
 * <p>生效规则：声明 ∩ 供给 ∩ 开关；交集不足（声明且启用但 Workflow 供不出）装配期即报错，
 * 不静默降级。</p>
 */
public enum AgentCapability {

    /** 流式生成：由管线用引擎回交的 GenerationSpec 做流式输出。 */
    STREAMING,

    /** 引用溯源：流式开始前下发 CitationIndex（[N] → 来源映射）。 */
    CITATIONS,

    /** 精确答案缓存：缓存 key 拼执行指纹（prompt 内容 hash 等）。 */
    ANSWER_CACHE,

    /** 语义答案缓存：同义问题命中重放。 */
    SEMANTIC_CACHE,

    /** 检索指标：保留检索明细供 eval（Recall@k 等）。 */
    RETRIEVAL_METRICS
}
