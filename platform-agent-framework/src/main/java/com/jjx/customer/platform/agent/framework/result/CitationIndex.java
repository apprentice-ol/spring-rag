package com.jjx.customer.platform.agent.framework.result;

import java.util.List;
import java.util.Optional;

/**
 * 引用索引（给前端与落库看的 [N] → 来源映射）。
 *
 * <p>时序硬约束：必须在流式生成开始前就绪（见 ExecutionMetadataSink）。</p>
 */
public record CitationIndex(List<Citation> citations) {

    public CitationIndex {
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    public static CitationIndex empty() {
        return new CitationIndex(List.of());
    }

    public boolean isEmpty() {
        return citations.isEmpty();
    }

    public Optional<Citation> byRef(int ref) {
        return citations.stream().filter(c -> c.ref() == ref).findFirst();
    }
}
