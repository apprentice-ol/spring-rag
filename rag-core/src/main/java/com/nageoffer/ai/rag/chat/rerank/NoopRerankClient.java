package com.nageoffer.ai.rag.chat.rerank;

import com.nageoffer.ai.rag.chat.retrieval.RetrievedChunk;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Noop Rerank 客户端（默认 fallback）。
 * <p>
 * 不执行实际的精排，直接截取前 topN 条返回。
 * 当未配置百炼 API key 或 rerank 功能关闭时使用。
 * </p>
 */
@Slf4j
public class NoopRerankClient implements RerankClient {

    @Override
    public String provider() {
        return "noop";
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (topN <= 0 || candidates.size() <= topN) {
            return candidates;
        }
        return candidates.subList(0, topN);
    }
}
