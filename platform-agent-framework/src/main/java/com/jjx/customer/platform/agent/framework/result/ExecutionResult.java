package com.jjx.customer.platform.agent.framework.result;

import com.jjx.customer.platform.agent.framework.trace.AgentTrace;

import java.util.Map;

/**
 * 引擎统一出口：内容 + 元数据 + 指纹（设计文档 §6）。
 *
 * <p>交付决策由 Agent 能力位决定（不是互斥的模式枚举）：
 * 声明 STREAMING 且 {@code generation != null} ⇒ 管线用生成规格流式输出；
 * 否则 {@code text} 非空即直接交付；两者皆无则按 {@link OutcomeKind} 走追问/升级。</p>
 *
 * @param metadata 结果元数据扩展（MetadataContributor 贡献；key = contributor 标识）
 * @param clarify  追问的结构化原因（缺哪些槽位 / 停在哪个阶段；非 CLARIFY 为 null）
 */
public record ExecutionResult(OutcomeKind kind,
                              String text,
                              ContextBundle context,
                              CitationIndex citations,
                              GenerationSpec generation,
                              ExecutionFingerprint fingerprint,
                              RetrievalStats retrievalStats,
                              AgentTrace trace,
                              Map<String, Object> metadata,
                              ClarifyInfo clarify) {

    public ExecutionResult {
        context = context == null ? ContextBundle.empty() : context;
        citations = citations == null ? CitationIndex.empty() : citations;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public ExecutionResult(OutcomeKind kind, String text, ContextBundle context, CitationIndex citations,
                           GenerationSpec generation, ExecutionFingerprint fingerprint,
                           RetrievalStats retrievalStats, AgentTrace trace) {
        this(kind, text, context, citations, generation, fingerprint, retrievalStats, trace, Map.of(), null);
    }

    public ExecutionResult(OutcomeKind kind, String text, ContextBundle context, CitationIndex citations,
                           GenerationSpec generation, ExecutionFingerprint fingerprint,
                           RetrievalStats retrievalStats, AgentTrace trace, Map<String, Object> metadata) {
        this(kind, text, context, citations, generation, fingerprint, retrievalStats, trace, metadata, null);
    }

    /** 直出型结果（无上下文、无生成规格）。 */
    public static ExecutionResult direct(String text, ExecutionFingerprint fingerprint, AgentTrace trace) {
        return new ExecutionResult(OutcomeKind.DIRECT, text, ContextBundle.empty(), CitationIndex.empty(),
                null, fingerprint, null, trace);
    }

    /** 上下文型结果（交管线生成；需配合 STREAMING 能力位）。 */
    public static ExecutionResult withContext(ContextBundle context, CitationIndex citations,
                                              GenerationSpec generation, ExecutionFingerprint fingerprint,
                                              RetrievalStats stats, AgentTrace trace) {
        return new ExecutionResult(OutcomeKind.WITH_CONTEXT, null, context, citations,
                generation, fingerprint, stats, trace);
    }

    /** 需要用户补充信息。 */
    public static ExecutionResult clarify(String askText, ExecutionFingerprint fingerprint, AgentTrace trace) {
        return clarify(askText, fingerprint, trace, null);
    }

    /**
     * 需要用户补充信息（带结构化缺失项）。
     *
     * <p>结构化信息供引擎落会话、交付层渲染追问卡片——使用方不需要再拿自己的槽位目录重算缺失项。</p>
     */
    public static ExecutionResult clarify(String askText, ExecutionFingerprint fingerprint, AgentTrace trace,
                                          ClarifyInfo clarify) {
        return new ExecutionResult(OutcomeKind.CLARIFY, askText, ContextBundle.empty(), CitationIndex.empty(),
                null, fingerprint, null, trace, Map.of(), clarify);
    }

    /** 升级/终止。 */
    public static ExecutionResult escalate(String reason, ExecutionFingerprint fingerprint, AgentTrace trace) {
        return new ExecutionResult(OutcomeKind.ESCALATE, reason, ContextBundle.empty(), CitationIndex.empty(),
                null, fingerprint, null, trace);
    }

    /** 追问的结构化原因（非 CLARIFY 或未携带时为 null）。 */
    public ClarifyInfo clarifyInfo() {
        return clarify;
    }

    /** 追加元数据贡献（MetadataContributor 收集后的拷贝工厂；保留追问结构）。 */
    public ExecutionResult withMetadata(Map<String, Object> contributed) {
        return new ExecutionResult(kind, text, context, citations, generation, fingerprint,
                retrievalStats, trace, contributed, clarify);
    }
}
