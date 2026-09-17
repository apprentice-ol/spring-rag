package com.jjx.customer.platform.agent.framework.result;

import java.util.List;

/**
 * 上下文产物（给模型看的证据集合）。
 *
 * <p>与 {@link CitationIndex} 是同一次检索的两个投影：本类给模型（文本+编号），
 * 引用索引给前端与落库（结构化映射）。</p>
 */
public record ContextBundle(List<ContextArtifact> artifacts) {

    public ContextBundle {
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    }

    public static ContextBundle empty() {
        return new ContextBundle(List.of());
    }

    public boolean isEmpty() {
        return artifacts.isEmpty();
    }
}
